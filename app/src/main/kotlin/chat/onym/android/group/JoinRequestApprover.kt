package chat.onym.android.group

import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.InvitationDecryptError
import chat.onym.android.transport.InboxTransport
import chat.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Sender-side: turn raw [IntroRequest]s into UI-renderable
 * [PendingRequest]s, and on user approval ship the actual sealed
 * [GroupInvitationPayload] to the joiner.
 *
 * Lifecycle:
 *  1. [start] subscribes to [IntroRequestStore.requests] and
 *     decrypts each newly-arrived envelope using the matching
 *     [IntroKeyEntry.introPrivateKey] from [IntroKeyStore]. Decrypt
 *     failures are logged via [_decryptFailures] (drives a
 *     debug-only counter; real users never see them).
 *  2. UI subscribes to [pending] and renders "X wants to join Y.
 *     Approve?" prompts.
 *  3. On Approve → seals the existing [GroupInvitationPayload]
 *     (built from the local [ChatGroup]) to the joiner's identity
 *     inbox key, ships via [inboxTransport.send], revokes the
 *     intro key. The fan-out from PR-3 stops listening on that
 *     intro tag within one emission window.
 *  4. On Decline → drop the request, revoke the intro key. No
 *     NACK to the joiner; their JoinScreen times out gracefully.
 */
/**
 * Test seam consumed by [ApproveRequestsViewModel]. The production
 * conformer is [JoinRequestApprover] itself; tests inject a stub
 * instead of standing up the full keychain + transport stack just to
 * exercise the VM's bookkeeping. Mirrors `JoinRequestApproving.swift`
 * from onym-ios.
 */
interface JoinRequestApproving {
    val pending: StateFlow<List<JoinRequestApprover.PendingRequest>>
    fun start()
    suspend fun approve(requestId: String): JoinRequestApprover.ApproveOutcome
    suspend fun decline(requestId: String)
}

open class JoinRequestApprover(
    private val identity: IdentityRepository,
    private val introKeyStore: IntroKeyStore,
    private val introRequestStore: IntroRequestStore,
    private val groupRepository: GroupRepository,
    private val inboxTransport: InboxTransport,
    private val scope: CoroutineScope,
) : JoinRequestApproving {
    /** UI-renderable view of one decrypted, awaiting-action request. */
    data class PendingRequest(
        /** Stable id == [IntroRequest.id]. Approve / Decline use it
         *  as the dedupe key. */
        val id: String,
        val joinerInboxPublicKey: ByteArray,
        /** 48-byte BLS pubkey when the joiner sent it (post-PR-78
         *  builds always do). `null` when the request came from a
         *  pre-PR-78 client; the approver still ships the invitation
         *  back, but skips the local roster update because there's
         *  no stable cross-device key to record under. */
        val joinerBlsPublicKey: ByteArray?,
        val joinerDisplayLabel: String,
        val groupId: ByteArray,
        /** Looked up from the local [GroupRepository]. Null if the
         *  joiner is asking about a group we don't know — surface
         *  a "this invite isn't for any group on this device"
         *  error in the UI rather than approving. */
        val groupName: String?,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is PendingRequest &&
                id == other.id &&
                joinerInboxPublicKey.contentEquals(other.joinerInboxPublicKey) &&
                (joinerBlsPublicKey?.contentEquals(other.joinerBlsPublicKey)
                    ?: (other.joinerBlsPublicKey == null)) &&
                joinerDisplayLabel == other.joinerDisplayLabel &&
                groupId.contentEquals(other.groupId) &&
                groupName == other.groupName)

        override fun hashCode(): Int {
            var h = id.hashCode()
            h = 31 * h + joinerInboxPublicKey.contentHashCode()
            h = 31 * h + (joinerBlsPublicKey?.contentHashCode() ?: 0)
            h = 31 * h + joinerDisplayLabel.hashCode()
            h = 31 * h + groupId.contentHashCode()
            h = 31 * h + (groupName?.hashCode() ?: 0)
            return h
        }
    }

    sealed class ApproveOutcome {
        object Sent : ApproveOutcome()
        object UnknownGroup : ApproveOutcome()
        object UnknownRequest : ApproveOutcome()
        object NoIdentityLoaded : ApproveOutcome()
        class TransportFailed(val reason: String) : ApproveOutcome()
    }

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    override val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    /** Internal counter for decrypt failures — drives a future
     *  diagnostic surface (e.g., Settings → Diagnostics shows
     *  "N requests failed to decrypt" so users can detect a forged
     *  link campaign or a corrupted intro key). */
    private val _decryptFailures = MutableStateFlow(0)

    @Suppress("unused")
    val decryptFailures: StateFlow<Int> = _decryptFailures.asStateFlow()

    /**
     * Subscribe to [IntroRequestStore.requests] and keep [pending]
     * in sync. Idempotent — safe to call once at app start. The
     * collector lives for [scope]'s lifetime.
     */
    override fun start() {
        scope.launch {
            introRequestStore.requests.collectLatest { raw ->
                val decoded = raw.mapNotNull { decode(it) }
                _pending.value = decoded
            }
        }
    }

    /** Test hook: decode + emit synchronously without spawning the
     *  collector. Lets unit tests assert the decode path without
     *  fighting collector scheduling. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun pumpOnce() {
        val raw = introRequestStore.requests.value
        _pending.value = raw.mapNotNull { decode(it) }
    }

    /**
     * Approve a pending request: build the [GroupInvitationPayload]
     * from the local group state, seal to the joiner's inbox key,
     * ship via Nostr, then revoke the intro slot + drop the
     * pending entry.
     */
    override suspend fun approve(requestId: String): ApproveOutcome = mutex.withLock {
        val req = _pending.value.firstOrNull { it.id == requestId }
            ?: return@withLock ApproveOutcome.UnknownRequest
        val activeIdentity = identity.currentIdentity()
            ?: return@withLock ApproveOutcome.NoIdentityLoaded

        val group = groupRepository.snapshots.value.firstOrNull {
            it.groupIdBytes.contentEquals(req.groupId)
        } ?: return@withLock ApproveOutcome.UnknownGroup

        val invitePayload = GroupInvitationPayload(
            version = 1,
            groupId = group.groupIdBytes,
            groupSecret = group.groupSecret,
            name = group.name,
            members = group.members,
            epoch = group.epoch,
            salt = group.salt,
            commitment = group.commitment,
            tierRaw = group.tier.rawValue,
            groupTypeRaw = group.groupType.wireValue,
            adminPubkeyHex = group.adminPubkeyHex,
            // Ship the directory-as-known so the joiner sees existing
            // peers + admin by name from the moment they land. The
            // joiner's own profile gets backfilled by the receiver's
            // materializer (PR 83) from their active identity.
            memberProfiles = group.memberProfiles.takeIf { it.isNotEmpty() },
        )
        val payloadBytes = try {
            jsonFormat.encodeToString(GroupInvitationPayload.serializer(), invitePayload)
                .toByteArray(Charsets.UTF_8)
        } catch (e: Throwable) {
            return@withLock ApproveOutcome.TransportFailed("encode: ${e.message ?: e.javaClass.simpleName}")
        }
        val sealed = try {
            identity.sealInvitation(payloadBytes, req.joinerInboxPublicKey)
        } catch (e: Throwable) {
            return@withLock ApproveOutcome.TransportFailed("seal: ${e.message ?: e.javaClass.simpleName}")
        }
        val joinerTag = TransportInboxId(IdentityRepository.inboxTag(req.joinerInboxPublicKey))
        val receipt = try {
            inboxTransport.send(sealed, joinerTag)
        } catch (e: Throwable) {
            return@withLock ApproveOutcome.TransportFailed("send: ${e.message ?: e.javaClass.simpleName}")
        }
        if (receipt.acceptedBy < 1) {
            return@withLock ApproveOutcome.TransportFailed("no relay accepted the invitation")
        }

        // Record the joiner in the local group's view-facing roster
        // (alias / inbox-pub) so the admin sees them by alias in the
        // UI. Skipped when the joiner shipped a pre-PR-78 request —
        // no stable cross-device key under which to record.
        val blsPub = req.joinerBlsPublicKey
        if (blsPub != null) {
            recordJoiner(
                group = group,
                blsPub = blsPub,
                inboxPub = req.joinerInboxPublicKey,
                alias = req.joinerDisplayLabel,
            )
            broadcastJoin(
                group = group,
                joinerBlsPub = blsPub,
                joinerInboxPub = req.joinerInboxPublicKey,
                joinerAlias = req.joinerDisplayLabel,
            )
        }

        // Best-effort cleanup. Both calls run regardless of failures
        // because the request is conceptually consumed at this point;
        // a leaked intro key is benign (sender ignores future
        // requests on it via UnknownGroup or just not approving).
        revokeAndConsume(introPub = findIntroPubFor(requestId), requestId = requestId)
        ApproveOutcome.Sent
    }

    /**
     * Insert / update the joiner's [MemberProfile] on the local
     * group. Idempotent — re-approving for the same `(blsPub, group)`
     * overwrites the entry (alias, inbox-pub) rather than minting a
     * duplicate. Goes through [GroupRepository.insert] which
     * delegates to [RoomGroupStore.insertOrUpdate].
     */
    private suspend fun recordJoiner(
        group: ChatGroup,
        blsPub: ByteArray,
        inboxPub: ByteArray,
        alias: String,
    ) {
        val key = blsPub.toHexLowercase()
        val updated = group.copy(
            memberProfiles = group.memberProfiles +
                (key to MemberProfile(alias = alias, inboxPublicKey = inboxPub)),
        )
        groupRepository.insert(updated)
    }

    /**
     * Build a [MemberAnnouncementPayload] for the new joiner and fan
     * it out to every existing member's inbox. Recipients =
     * `group.memberProfiles ∖ {admin, new joiner}`. The admin already
     * knows about the join (just recorded it locally); the joiner
     * gets the full [GroupInvitationPayload] instead.
     *
     * Best-effort per recipient: a per-member transport failure is
     * swallowed silently and the loop moves on. The receive-side is
     * idempotent on `(groupId, blsPub)` so a future retry path could
     * re-broadcast without creating duplicates.
     *
     * Empty fanout (single-member group) is a no-op.
     */
    private suspend fun broadcastJoin(
        group: ChatGroup,
        joinerBlsPub: ByteArray,
        joinerInboxPub: ByteArray,
        joinerAlias: String,
    ) {
        // Identity has no display-name field on Android — the
        // per-identity summary carries it. Fall back to empty when
        // unresolved (best-effort — receivers always display the
        // BLS fingerprint alongside the alias).
        val activeId = identity.currentIdentityId.value
        val adminAlias = identity.identities.value
            .firstOrNull { it.id == activeId }
            ?.name
            .orEmpty()
        val payload = try {
            MemberAnnouncementPayload(
                version = 1,
                groupId = group.groupIdBytes,
                newMember = MemberAnnouncementPayload.AnnouncedMember(
                    blsPub = joinerBlsPub,
                    inboxPub = joinerInboxPub,
                    alias = joinerAlias,
                ),
                adminAlias = adminAlias,
                // PR 88 fills these for Tyranny anchors. Until then,
                // ship without — receivers fall back to best-effort
                // acceptance for non-Tyranny / pre-PR-88 announcements.
                commitment = null,
                epoch = null,
            )
        } catch (_: Throwable) {
            // Wrong-sized BLS / inbox pub shouldn't happen — caller
            // already used the same bytes for recordJoiner — but
            // skipping fanout is safer than crashing.
            return
        }
        val payloadBytes = try {
            jsonFormat.encodeToString(MemberAnnouncementPayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            return
        }

        val joinerKey = joinerBlsPub.toHexLowercase()
        val adminKey = group.adminPubkeyHex?.lowercase()

        for ((memberKey, profile) in group.memberProfiles) {
            // Skip self (admin) + the new joiner (covered by the
            // GroupInvitationPayload above).
            if (memberKey == joinerKey) continue
            if (adminKey != null && memberKey == adminKey) continue

            val sealed = try {
                identity.sealInvitation(payloadBytes, profile.inboxPublicKey)
            } catch (_: Throwable) {
                continue
            }
            val tag = TransportInboxId(IdentityRepository.inboxTag(profile.inboxPublicKey))
            // Throw away the receipt — fanout is best-effort. A
            // member that misses one announcement will still see the
            // joiner in any subsequent group activity.
            runCatching { inboxTransport.send(sealed, tag) }
        }
    }

    /** Decline a pending request: drop it + revoke the intro slot.
     *  No NACK to the joiner — their JoinScreen times out. */
    override suspend fun decline(requestId: String): Unit = mutex.withLock {
        revokeAndConsume(introPub = findIntroPubFor(requestId), requestId = requestId)
    }

    // ─── private ──────────────────────────────────────────────────

    private suspend fun decode(raw: IntroRequest): PendingRequest? {
        // We hold the introPub on the IntroRequest from PR-3's
        // pump. Look up the privkey via IntroKeyStore. If the
        // privkey is missing (e.g., the entry was already revoked
        // before we got here), drop the request silently.
        val entry = introKeyStore.find(raw.targetIntroPublicKey) ?: return null

        val plaintext = try {
            IdentityRepository.decryptSealedEnvelopeWithKey(
                envelopeBytes = raw.payload,
                recipientX25519PrivateKey = entry.introPrivateKey,
            )
        } catch (e: InvitationDecryptError) {
            _decryptFailures.value += 1
            return null
        } catch (e: Throwable) {
            _decryptFailures.value += 1
            return null
        }
        val payload = try {
            jsonFormat.decodeFromString(JoinRequestPayload.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            _decryptFailures.value += 1
            return null
        } catch (_: IllegalArgumentException) {
            _decryptFailures.value += 1
            return null
        }
        if (!payload.groupId.contentEquals(entry.groupId)) {
            // Joiner is asking about a different group than the
            // intro entry was minted for. Forged or stale link —
            // drop silently.
            _decryptFailures.value += 1
            return null
        }

        val groupName = groupRepository.snapshots.value
            .firstOrNull { it.groupIdBytes.contentEquals(payload.groupId) }
            ?.name

        return PendingRequest(
            id = raw.id,
            joinerInboxPublicKey = payload.joinerInboxPublicKey,
            joinerBlsPublicKey = payload.joinerBlsPublicKey,
            joinerDisplayLabel = payload.joinerDisplayLabel,
            groupId = payload.groupId,
            groupName = groupName,
        )
    }

    private suspend fun findIntroPubFor(requestId: String): ByteArray? {
        // PendingRequest doesn't carry the introPub (intentional —
        // UI should never need it). Resolve via the raw store.
        val raw = introRequestStore.requests.value.firstOrNull { it.id == requestId }
            ?: return null
        return raw.targetIntroPublicKey
    }

    private suspend fun revokeAndConsume(introPub: ByteArray?, requestId: String) {
        if (introPub != null) introKeyStore.revoke(introPub)
        introRequestStore.consume(requestId)
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** Lowercase hex of a [ByteArray]. Lives here so the
         *  approver doesn't have to import the persistence /
         *  transport layer's privates. Mirrors the
         *  `String(format: "%02x", $0)` map used on iOS. */
        fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
