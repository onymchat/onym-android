package app.onym.android.group

import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.GroupProofGenerator
import app.onym.android.chain.GroupProofGeneratorError
import app.onym.android.chain.GroupProofUpdateInput
import app.onym.android.chain.NetworkPreferenceProvider
import app.onym.android.chain.OkHttpSepContractTransport
import app.onym.android.chain.OnymGroupProofGenerator
import app.onym.android.chain.RelayerRepository
import app.onym.android.chain.SepContractClient
import app.onym.android.chain.SepContractError
import app.onym.android.chain.SepContractTransport
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.TyrannyUpdateCommitmentPayload
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.identity.InvitationDecryptError
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
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
 *     inbox key and ships via [inboxTransport.send]. The intro key
 *     is deliberately NOT retired: invite links are multi-use, so
 *     the same [IntroKeyEntry] must keep decrypting requests from
 *     everyone else the inviter shared the link with. A link is
 *     retired only when the inviter revokes or rotates it.
 *  4. On Decline → drop that one request. Nothing else: the link
 *     stays live so declining Bob can't silently lock out Carol.
 *     No NACK to the joiner; their JoinScreen times out gracefully.
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
    /** Gate for "is an identity selected", plus the fanout's
     *  self-skip. Narrowed from the concrete [IdentityRepository]
     *  (which conforms) so JVM unit tests can drive approve/decline
     *  without the Android Keystore and the OnymSDK JNI. */
    private val activeIdentity: ActiveIdentityProvider,
    private val envelopeSealer: InvitationEnvelopeSealer,
    /** Reads the admin's BLS secret for the Tyranny update proof.
     *  Production passes `identityRepository::blsSecretKey`; the read
     *  site is in [anchorTyrannyJoin]. */
    private val blsSecretKey: suspend () -> ByteArray,
    private val introKeyStore: IntroKeyStore,
    private val introRequestStore: IntroRequestStore,
    private val groupRepository: GroupRepository,
    private val inboxTransport: InboxTransport,
    private val scope: CoroutineScope,
    /** Display names for the fanout's `adminAlias`. Production passes
     *  `identityRepository.identities`; tests pass a static flow. */
    private val identitySummaries: StateFlow<List<IdentitySummary>> =
        MutableStateFlow(emptyList()),
    /** Chain-relayer dependencies for the on-chain anchor flow. PR 88
     *  drives [anchorTyrannyJoin] through these. Optional so existing
     *  unit tests that don't need the anchor leg can keep working. */
    private val relayers: RelayerRepository? = null,
    private val contracts: ContractsRepository? = null,
    private val networkPreference: NetworkPreferenceProvider? = null,
    private val proofGenerator: GroupProofGenerator = OnymGroupProofGenerator(),
    /** Builds a [SepContractTransport] from the relayer URL chosen
     *  per-call. Injected so tests can swap a fake without touching
     *  OkHttp. */
    private val makeContractTransport: (String) -> SepContractTransport = { url ->
        OkHttpSepContractTransport(httpClient = OkHttpClient(), endpointUrl = url)
    },
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
        /** 32-byte Poseidon leaf hash. Required for the on-chain
         *  Tyranny `update_commitment` proof (PR 88). `null` from
         *  pre-PR-88 clients — those approve attempts surface as
         *  [ApproveOutcome.OutdatedJoinerClient]. */
        val joinerLeafHash: ByteArray? = null,
        /** 32-byte Ed25519 envelope-signing pubkey from the joiner.
         *  PR A3 hard-cutover: required on every request so the
         *  admin can stamp it into the joiner's [MemberProfile] for
         *  PR A4's chat-signature verification path. */
        val joinerSendingPublicKey: ByteArray,
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
                (joinerLeafHash?.contentEquals(other.joinerLeafHash)
                    ?: (other.joinerLeafHash == null)) &&
                joinerSendingPublicKey.contentEquals(other.joinerSendingPublicKey) &&
                joinerDisplayLabel == other.joinerDisplayLabel &&
                groupId.contentEquals(other.groupId) &&
                groupName == other.groupName)

        override fun hashCode(): Int {
            var h = id.hashCode()
            h = 31 * h + joinerInboxPublicKey.contentHashCode()
            h = 31 * h + (joinerBlsPublicKey?.contentHashCode() ?: 0)
            h = 31 * h + (joinerLeafHash?.contentHashCode() ?: 0)
            h = 31 * h + joinerSendingPublicKey.contentHashCode()
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
        /** Joiner shipped a pre-PR-88 request without
         *  `joiner_leaf_hash`. Admin can't extend the on-chain tree
         *  without it; user must ask the joiner to update. */
        object OutdatedJoinerClient : ApproveOutcome()
        /** [RelayerRepository.selectUrl] returned null. */
        object NoActiveRelayer : ApproveOutcome()
        /** No deployed Tyranny contract for the active network. */
        object NoContractBinding : ApproveOutcome()
        /** Active identity isn't this group's admin (PR 93). */
        object NotAdminOfThisGroup : ApproveOutcome()
        /** `Tyranny.proveUpdate` failed — corrupted roster, wrong
         *  tier depth, SDK FFI error, etc. */
        class ProofFailed(val reason: String) : ApproveOutcome()
        /** Relayer accepted the POST but the contract rejected. */
        class AnchorRejected(val reason: String) : ApproveOutcome()
    }

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    override val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    /** Internal counter for decrypt failures — drives a future
     *  diagnostic surface (e.g., Settings → Diagnostics shows
     *  "N requests failed to decrypt" so users can detect a forged
     *  link campaign or a corrupted intro key). */
    private val _decryptFailures = MutableStateFlow(0)

    /** Surviving pending-row id → every raw [IntroRequest.id] that
     *  collapsed into it (the winner included). Rebuilt by [refresh],
     *  read by [consumeRequestAndSiblings]. A StateFlow rather than a
     *  plain var so the collector coroutine's write is visible to the
     *  approve/decline caller without extra synchronisation. */
    private val collapsedIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    @Suppress("unused")
    val decryptFailures: StateFlow<Int> = _decryptFailures.asStateFlow()

    /**
     * Subscribe to [IntroRequestStore.requests] and keep [pending]
     * in sync. Idempotent — safe to call once at app start. The
     * collector lives for [scope]'s lifetime.
     */
    override fun start() {
        scope.launch {
            introRequestStore.requests.collectLatest { raw -> refresh(raw) }
        }
    }

    /** Test hook: decode + emit synchronously without spawning the
     *  collector. Lets unit tests assert the decode path without
     *  fighting collector scheduling. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun pumpOnce() {
        refresh(introRequestStore.requests.value)
    }

    /**
     * Decode every raw request and collapse the copies that represent
     * the same logical join — same joiner identity, same group.
     *
     * The joiner re-taps the deeplink or re-opens the join screen, and
     * the relay replays the whole intro tag on every reconnect. Each
     * copy is a distinct Nostr event with its own id, because
     * `NostrInboxTransport.send` signs with a fresh ephemeral key per
     * send, so [IntroRequestStore]'s event-id dedup can't catch them.
     * Without this, one person retrying shows up as several rows and
     * acting on one leaves the siblings behind — which reads as "the
     * buttons don't work".
     *
     * Keeps the most recently received copy, positioned at the
     * first-seen index: `LinkedHashMap` preserves first-insert order
     * even when the value is later replaced.
     *
     * Identity key is `joinerBlsPublicKey ?: joinerInboxPublicKey` —
     * the BLS key is the stable cross-device choice, the inbox key the
     * pre-PR-78 fallback.
     *
     * Mirrors `refresh(from:)` in onym-ios `JoinRequestApprover.swift`.
     */
    private suspend fun refresh(raw: List<IntroRequest>) {
        val winners = LinkedHashMap<String, Pair<PendingRequest, IntroRequest>>()
        val siblings = LinkedHashMap<String, MutableList<String>>()
        for (r in raw) {
            val decoded = decode(r) ?: continue
            val joinerKey = decoded.joinerBlsPublicKey ?: decoded.joinerInboxPublicKey
            val key = joinerKey.toHexLowercase() + ":" + decoded.groupId.toHexLowercase()
            siblings.getOrPut(key) { mutableListOf() } += r.id
            val incumbent = winners[key]
            if (incumbent == null || r.receivedAt.isAfter(incumbent.second.receivedAt)) {
                winners[key] = decoded to r
            }
        }
        _pending.value = winners.values.map { it.first }
        collapsedIds.value = winners.entries.associate { (key, win) ->
            win.first.id to (siblings[key]?.toList() ?: listOf(win.first.id))
        }
    }

    /**
     * Approve a pending request: build the [GroupInvitationPayload]
     * from the local group state, seal to the joiner's inbox key,
     * ship via Nostr, then drop the pending entry. The intro key
     * survives — invite links are multi-use.
     *
     * A joiner already in `group.members` skips the anchor leg and
     * only gets the current snapshot re-shipped.
     */
    override suspend fun approve(requestId: String): ApproveOutcome = mutex.withLock {
        val req = _pending.value.firstOrNull { it.id == requestId }
            ?: return@withLock ApproveOutcome.UnknownRequest
        // Pure gate — the approver needs an identity selected, but
        // nothing downstream reads which one.
        if (activeIdentity.currentIdentityId.value == null) {
            return@withLock ApproveOutcome.NoIdentityLoaded
        }

        val group = groupRepository.snapshots.value.firstOrNull {
            it.groupIdBytes.contentEquals(req.groupId)
        } ?: return@withLock ApproveOutcome.UnknownGroup

        // Re-join recovery path: this joiner is ALREADY in the
        // group's cryptographic roster. They reinstalled and lost
        // local state, the relay replayed an old request on this
        // still-live intro tag, or an earlier approve anchored and
        // then failed at seal+ship. Extending the Merkle tree again
        // would push a duplicate leaf and burn an epoch for nothing,
        // so skip the anchor and just re-seal + re-ship the current
        // snapshot below.
        //
        // Keyed on `members` — the roster [anchorTyrannyJoin] actually
        // mutates — and not on `memberProfiles`, which is an
        // independent app-level directory that can hold entries with
        // no corresponding leaf. A null BLS pubkey yields false,
        // preserving today's behaviour exactly: Tyranny already
        // short-circuits to OutdatedJoinerClient, and the ship-only
        // path already gates its side effects on `blsPub != null`.
        //
        // This also hardens the double-tapped-Approve race: `approve`
        // reads its row from `_pending`, which the collector only
        // refreshes asynchronously, so two taps can both find it. The
        // mutex serializes them, so the second pass reads the roster
        // the first pass persisted and degrades to a safe re-ship.
        val blsPub = req.joinerBlsPublicKey
        val alreadyInRoster = blsPub != null && group.members.any {
            it.publicKeyCompressed.contentEquals(blsPub)
        }

        // PR 88 admin-anchor leg — Tyranny only. Other governance
        // types fall through to the pre-PR-88 ship-only flow below.
        var anchored = group
        if (group.groupType == SepGroupType.TYRANNY && !alreadyInRoster) {
            when (val outcome = anchorTyrannyJoin(req, group)) {
                is AnchorOutcome.Failed -> return@withLock outcome.outcome
                is AnchorOutcome.Ok -> {
                    anchored = outcome.group
                    // Persist the advanced state immediately so a
                    // subsequent crash before seal+ship doesn't lose
                    // the chain transition.
                    groupRepository.insert(anchored)
                }
            }
        }

        val invitePayload = GroupInvitationPayload(
            version = 1,
            groupId = anchored.groupIdBytes,
            groupSecret = anchored.groupSecret,
            name = anchored.name,
            members = anchored.members,
            epoch = anchored.epoch,
            salt = anchored.salt,
            commitment = anchored.commitment,
            tierRaw = anchored.tier.rawValue,
            groupTypeRaw = anchored.groupType.wireValue,
            adminPubkeyHex = anchored.adminPubkeyHex,
            // Ship the directory-as-known so the joiner sees existing
            // peers + admin by name from the moment they land. The
            // joiner's own profile gets backfilled by the receiver's
            // materializer (PR 83) from their active identity.
            memberProfiles = anchored.memberProfiles.takeIf { it.isNotEmpty() },
            // Carry the group photo so a create-time member sees it the
            // moment the snapshot lands — the only delivery path for
            // members who join via the full snapshot rather than a later
            // GroupAvatarPayload. `null` when the group has no photo.
            avatar = anchored.avatar,
            // Carry the group's invitation/intro so it persists for the
            // joiner once they materialize the group.
            invitationMessage = anchored.invitationMessage,
        )
        val payloadBytes = try {
            jsonFormat.encodeToString(GroupInvitationPayload.serializer(), invitePayload)
                .toByteArray(Charsets.UTF_8)
        } catch (e: Throwable) {
            return@withLock ApproveOutcome.TransportFailed("encode: ${e.message ?: e.javaClass.simpleName}")
        }
        val sealed = try {
            envelopeSealer.sealInvitation(payloadBytes, req.joinerInboxPublicKey)
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
        // no stable cross-device key under which to record. Use the
        // post-anchor group snapshot so PR-88's `commitment + epoch`
        // ship in the announcement.
        if (blsPub != null) {
            // Idempotent on the BLS hex key. On the re-join path this
            // is the whole point of still calling it: a reinstalled
            // joiner has a new inbox pubkey, and future fanouts read
            // it from here.
            recordJoiner(
                group = anchored,
                blsPub = blsPub,
                inboxPub = req.joinerInboxPublicKey,
                sendingPub = req.joinerSendingPublicKey,
                alias = req.joinerDisplayLabel,
            )
            // Kept on the re-join path too, unlike iOS. Receivers
            // dedup it for free (applyAnnouncement bails on a known
            // non-revoked BLS hex before any chain read), and it is
            // the only thing that heals peers after an approve that
            // anchored and then failed at seal+ship — that retry sees
            // the joiner already in `members`.
            broadcastJoin(
                group = anchored,
                joinerBlsPub = blsPub,
                joinerInboxPub = req.joinerInboxPublicKey,
                joinerSendingPub = req.joinerSendingPublicKey,
                joinerAlias = req.joinerDisplayLabel,
            )
        }

        // Drop this request and its collapsed siblings. The intro key
        // is intentionally left alive: one invite link is redeemable
        // by many joiners until the inviter retires it, and revoking here
        // would make `decode` return null for every other joiner's
        // pending request — their rows would vanish from the approval
        // surface with no explanation.
        consumeRequestAndSiblings(requestId)
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
        sendingPub: ByteArray,
        alias: String,
    ) {
        val key = blsPub.toHexLowercase()
        val updated = group.copy(
            memberProfiles = group.memberProfiles +
                (key to MemberProfile(
                    alias = alias,
                    inboxPublicKey = inboxPub,
                    sendingPubkey = sendingPub,
                )),
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
        joinerSendingPub: ByteArray,
        joinerAlias: String,
    ) {
        // Identity has no display-name field on Android — the
        // per-identity summary carries it. Fall back to empty when
        // unresolved (best-effort — receivers always display the
        // BLS fingerprint alongside the alias).
        val activeId = activeIdentity.currentIdentityId.value
        val adminAlias = identitySummaries.value
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
                    sendingPub = joinerSendingPub,
                ),
                adminAlias = adminAlias,
                // PR 88: ship the post-anchor commitment + epoch so
                // PR 89's receivers can verify against
                // SEPContractClient.getCommitment. Null only when
                // the calling group hasn't been anchored (legacy /
                // non-Tyranny path) — receivers fall back to
                // best-effort acceptance in that case.
                commitment = group.commitment.takeIf {
                    group.groupType == SepGroupType.TYRANNY
                },
                epoch = if (group.groupType == SepGroupType.TYRANNY) group.epoch else null,
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
                envelopeSealer.sealInvitation(payloadBytes, profile.inboxPublicKey)
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

    /** Decline a pending request: drop that one request and its
     *  collapsed siblings, and nothing else.
     *
     *  The invite link stays live. A decline is a judgement about one
     *  requester, not about the link — declining Bob must not silently
     *  lock out Carol, who is holding the same shared link. Killing the
     *  link is a separate, deliberate action on the share screen.
     *
     *  No NACK to the joiner — their JoinScreen times out. */
    override suspend fun decline(requestId: String): Unit = mutex.withLock {
        consumeRequestAndSiblings(requestId)
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
            joinerLeafHash = payload.joinerLeafHash,
            joinerSendingPublicKey = payload.joinerSendingPublicKey,
            joinerDisplayLabel = payload.joinerDisplayLabel,
            groupId = payload.groupId,
            groupName = groupName,
        )
    }

    /** Drop the request behind [requestId] plus every sibling copy
     *  that collapsed into the same pending row (see [refresh]).
     *  Consuming only the winner would let a sibling surface as a
     *  fresh row on the very next emission.
     *
     *  The intro key is deliberately NOT revoked: links are multi-use
     *  until the inviter retires them. */
    private suspend fun consumeRequestAndSiblings(requestId: String) {
        val ids = collapsedIds.value[requestId] ?: listOf(requestId)
        for (id in ids) introRequestStore.consume(id)
    }

    /** Outcome shape for [anchorTyrannyJoin]. */
    private sealed class AnchorOutcome {
        data class Ok(val group: ChatGroup) : AnchorOutcome()
        data class Failed(val outcome: ApproveOutcome) : AnchorOutcome()
    }

    /**
     * On-chain anchor leg of [approve] — Tyranny only. Returns the
     * updated [ChatGroup] (post-anchor) on success, or an
     * [ApproveOutcome] describing the failure on any short-circuit.
     * Pure: never mutates local state. Caller persists.
     *
     * Mirrors `anchorTyrannyJoin` from onym-ios PR #88.
     */
    private suspend fun anchorTyrannyJoin(
        req: PendingRequest,
        group: ChatGroup,
    ): AnchorOutcome {
        val joinerBlsPub = req.joinerBlsPublicKey
        val joinerLeafHash = req.joinerLeafHash
        if (joinerBlsPub == null || joinerLeafHash == null) {
            return AnchorOutcome.Failed(ApproveOutcome.OutdatedJoinerClient)
        }
        val adminPubkeyHex = group.adminPubkeyHex
            ?: return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("group missing adminPubkeyHex"),
            )
        val relayerUrl = relayers?.selectUrl()
            ?: return AnchorOutcome.Failed(ApproveOutcome.NoActiveRelayer)
        val networkPref = networkPreference?.current()
            ?: return AnchorOutcome.Failed(ApproveOutcome.NoContractBinding)
        val contractsRepo = contracts
            ?: return AnchorOutcome.Failed(ApproveOutcome.NoContractBinding)
        val key = AnchorSelectionKey(
            network = networkPref.contractNetwork,
            type = GovernanceType.Tyranny,
        )
        val binding = contractsRepo.snapshots.value.binding(key)
            ?: return AnchorOutcome.Failed(ApproveOutcome.NoContractBinding)

        // Resolve admin's index in the OLD member roster.
        val adminBytes = ChatGroup.bytesFromHex(adminPubkeyHex)
        val adminIndexOld = group.members.indexOfFirst {
            it.publicKeyCompressed.contentEquals(adminBytes)
        }
        if (adminIndexOld < 0) {
            return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("admin not in members roster"),
            )
        }

        // Build new lex-sorted member list including the joiner.
        // Compute the new Poseidon root over the new tree.
        val joinerMember = GovernanceMember(
            publicKeyCompressed = joinerBlsPub,
            leafHash = joinerLeafHash,
        )
        val newMembers = (group.members + joinerMember)
            .sortedWith(compareBy(byteArrayLexComparator()) { it.publicKeyCompressed })
        val memberRootNew = try {
            GroupCommitmentBuilder.computeMerkleRoot(
                members = newMembers,
                tier = group.tier,
            )
        } catch (e: Throwable) {
            return AnchorOutcome.Failed(ApproveOutcome.ProofFailed("merkle_root: ${e.message ?: e}"))
        }
        val saltNew = GroupCommitmentBuilder.generateSalt()

        val blsSecret = try {
            // onym:allow-secret-read
            blsSecretKey()
        } catch (e: Throwable) {
            return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("bls_secret: ${e.message ?: e}"),
            )
        }

        // PR 93 pre-flight: confirm the active identity actually IS
        // the admin of this group before handing the secret to the
        // prover. Catches the common "user switched identities since
        // group creation" case cleanly — without this check the SDK
        // surfaces the same problem as a cryptic
        // `Poseidon(admin_secret_key) != supplied leaf hash` error
        // ~3-5s later (after the prover's pre-witness checks fail).
        val activePubFromSecret = try {
            GroupCommitmentBuilder.computePublicKey(blsSecret)
        } catch (e: Throwable) {
            return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("derive_pub: ${e.message ?: e::class.simpleName}"),
            )
        }
        if (!activePubFromSecret.contentEquals(group.members[adminIndexOld].publicKeyCompressed)) {
            return AnchorOutcome.Failed(ApproveOutcome.NotAdminOfThisGroup)
        }

        val proofInput = GroupProofUpdateInput(
            groupType = SepGroupType.TYRANNY,
            tier = group.tier,
            oldMembers = group.members,
            adminBlsSecretKey = blsSecret,
            adminIndexOld = adminIndexOld,
            epochOld = group.epoch,
            memberRootNew = memberRootNew,
            groupId = group.groupIdBytes,
            saltOld = group.salt,
            saltNew = saltNew,
        )
        val proof = try {
            proofGenerator.proveUpdate(proofInput)
        } catch (e: GroupProofGeneratorError) {
            return AnchorOutcome.Failed(
                ApproveOutcome.ProofFailed(e.message ?: e.javaClass.simpleName),
            )
        } catch (e: Throwable) {
            return AnchorOutcome.Failed(
                ApproveOutcome.ProofFailed(e.message ?: e.toString()),
            )
        }

        val transport = makeContractTransport(relayerUrl)
        val client = SepContractClient(
            contractID = binding.contractId,
            contractType = SepGroupType.TYRANNY,
            network = networkPref.sepNetwork,
            transport = transport,
        )
        val payload = TyrannyUpdateCommitmentPayload(
            groupId = group.groupIdBytes,
            proof = proof.proof,
            publicInputs = proof.publicInputs,
        )
        val response = try {
            client.updateCommitmentTyranny(payload)
        } catch (e: SepContractError) {
            return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("anchor: ${e.message ?: e}"),
            )
        } catch (e: Throwable) {
            return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("anchor: ${e.message ?: e}"),
            )
        }
        if (!response.accepted) {
            return AnchorOutcome.Failed(
                ApproveOutcome.AnchorRejected(response.message ?: "(no message)"),
            )
        }

        return AnchorOutcome.Ok(
            group.copy(
                members = newMembers,
                commitment = proof.commitmentNew,
                epoch = group.epoch + 1uL,
                salt = saltNew,
            ),
        )
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** Lex comparator over [ByteArray]; matches the canonical
         *  member ordering already used in [CreateGroupInteractor]. */
        private fun byteArrayLexComparator(): Comparator<ByteArray> =
            Comparator { a, b ->
                val len = minOf(a.size, b.size)
                for (i in 0 until len) {
                    val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                    if (cmp != 0) return@Comparator cmp
                }
                a.size - b.size
            }

        /** Lowercase hex of a [ByteArray]. Lives here so the
         *  approver doesn't have to import the persistence /
         *  transport layer's privates. Mirrors the
         *  `String(format: "%02x", $0)` map used on iOS. */
        fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
