package app.onym.android.group

import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GovernanceMember
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.GroupProofGenerator
import app.onym.android.chain.GroupProofGeneratorError
import app.onym.android.chain.GroupProofUpdateInput
import app.onym.android.chain.NetworkPreferenceProvider
import app.onym.android.chain.OkHttpSepContractTransport
import app.onym.android.chain.OnymGroupProofGenerator
import app.onym.android.chain.RelayerRepository
import app.onym.android.chain.SepCommitmentEntry
import app.onym.android.chain.SepContractClient
import app.onym.android.chain.SepContractError
import app.onym.android.chain.SepContractErrorCode
import app.onym.android.chain.SepContractTransport
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
 *     inbox key and ships it. The intro key is NOT retired.
 *  4. On Decline → drop that one request; the link stays live.
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
    /** Narrowed from [IdentityRepository] so JVM tests can drive
     *  approve/decline without the Keystore and the OnymSDK JNI. */
    private val activeIdentity: ActiveIdentityProvider,
    private val envelopeSealer: InvitationEnvelopeSealer,
    /** Admin's BLS secret for the update proof; the read site is
     *  [anchorTyrannyJoin]. */
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
    /**
     * Where a member-add's new salt is written down before the
     * transaction using it goes out. See [PendingAnchor] for why a
     * lost answer is otherwise unrecoverable.
     */
    private val pendingAnchors: PendingAnchorStore = NoopPendingAnchorStore,
    /**
     * `Poseidon(Poseidon(root, epoch), salt)` over a roster — the same
     * commitment shape the contract stores.
     *
     * Only the reconcile path needs it, to ask "is the state the chain
     * holds one this device can still account for?". Injected because
     * the production answer goes through the OnymSDK JNI and the
     * reconcile decisions are worth testing on the JVM.
     */
    private val commitmentOf: CommitmentRecomputing = { members, tier, epoch, salt ->
        GroupCommitmentBuilder.computePoseidonCommitment(
            poseidonRoot = GroupCommitmentBuilder.computeMerkleRoot(members, tier),
            epoch = epoch,
            salt = salt,
        )
    },
    /** Appends the "X joined" notice to the admin's own copy of the
     *  thread once an approval lands. Every other member gets theirs
     *  from the fanned-out [MemberAnnouncementPayload]; the admin never
     *  receives that (it is the sender), so this is the admin's only
     *  source for the row. */
    private val systemEvents: GroupSystemEventRecording = NoopGroupSystemEventRecorder,
) : JoinRequestApproving {
    /**
     * Whether the joiner agreed to the group's rules, decided once when
     * the request is decrypted and against the group's own copy of the
     * text.
     *
     * Five outcomes rather than a `Boolean`, because a founder deciding
     * on a stranger needs to tell apart the cases a boolean collapses:
     * someone who signed nothing (an older build) is not someone whose
     * signature failed to verify (forged or corrupt), and neither is
     * someone who agreed to a previous wording. Only one of the three is
     * worth asking the joiner to redo.
     */
    enum class RulesAgreement {
        /** The group has no rules, so there was nothing to agree to. */
        NOT_REQUIRED,

        /** Verified against the rules this group holds right now. */
        AGREED,

        /**
         * A signature this device cannot check, because the hash it
         * carries is not the hash of any rules we hold.
         *
         * Named for what is known rather than for what it suggests. The
         * earlier name — "agreed to other rules" — asserted an agreement
         * nothing here verified: the only evidence is the joiner's *own*
         * hash differing from ours, which they choose. Sixty-four random
         * bytes and a random hash landed in this case, and it was the
         * reassuring one, so a forgery could pick its own verdict simply
         * by not echoing our hash. It reads as "can't be checked" now,
         * and is coloured accordingly.
         */
        UNKNOWN_RULES,

        /** A signature that doesn't verify. Not an old client: an old
         *  client sends none at all. */
        INVALID,

        /** The group has rules and the request carried no agreement. */
        NOT_SIGNED,
    }

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
        /** What the joiner agreed to, checked against the group's rules
         *  at decrypt time. Never blocks approval on its own — the
         *  founder is shown it and decides, because rejecting outright
         *  would silently exclude every joiner on a build that predates
         *  rules. */
        val rulesAgreement: RulesAgreement,
        /** The signature itself, kept so approval can record it on the
         *  member. Retained rather than re-derived: this is the
         *  evidence. */
        val rulesSignature: ByteArray?,
        /** The hash the joiner signed over — not necessarily the hash of
         *  this group's current rules; see [RulesAgreement].
         *
         *  No defaults on these three: forgetting the verdict would read
         *  as "this group has no rules", which is a claim, and the
         *  compiler is the cheapest place to catch it. */
        val rulesHash: ByteArray?,
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
                groupName == other.groupName &&
                // The verdict and its evidence are part of what this
                // row *is*. Left out, a re-decode whose only change is
                // the agreement compares equal, the `pending` StateFlow
                // skips the update, and the founder keeps reading a
                // verdict reached before the group had loaded.
                rulesAgreement == other.rulesAgreement &&
                (rulesSignature?.contentEquals(other.rulesSignature)
                    ?: (other.rulesSignature == null)) &&
                (rulesHash?.contentEquals(other.rulesHash) ?: (other.rulesHash == null)))

        override fun hashCode(): Int {
            var h = id.hashCode()
            h = 31 * h + joinerInboxPublicKey.contentHashCode()
            h = 31 * h + (joinerBlsPublicKey?.contentHashCode() ?: 0)
            h = 31 * h + (joinerLeafHash?.contentHashCode() ?: 0)
            h = 31 * h + joinerSendingPublicKey.contentHashCode()
            h = 31 * h + joinerDisplayLabel.hashCode()
            h = 31 * h + groupId.contentHashCode()
            h = 31 * h + rulesAgreement.hashCode()
            h = 31 * h + (rulesSignature?.contentHashCode() ?: 0)
            h = 31 * h + (rulesHash?.contentHashCode() ?: 0)
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

        /** The contract has no record of this group yet
         *  (`GROUP_NOT_FOUND`). Almost always a race rather than a
         *  fault: the group's own `create_group` transaction is still
         *  waiting to be included in a ledger, and the admin approved a
         *  joiner within those few seconds. Retrying shortly succeeds,
         *  so this is separated from [AnchorRejected] — which means "the
         *  chain looked at this and said no" and is not worth
         *  retrying. */
        object GroupNotAnchoredYet : ApproveOutcome()

        /**
         * The contract refused because the state this device proved
         * *from* is not the state it holds (`PUBLIC_INPUTS_MISMATCH`),
         * and the gap could not be closed by reading the chain back.
         *
         * Distinct from [AnchorRejected] because the chain did not
         * judge the joiner or the proof — it judged this device's copy
         * of the group, and the founder can do something about that
         * (approve from the device that last anchored, or restore it)
         * where "the chain said no" leaves them nowhere.
         */
        class StaleGroupState(
            val localEpoch: ULong,
            val chainEpoch: ULong,
        ) : ApproveOutcome()
    }

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    override val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    /** Internal counter for decrypt failures — drives a future
     *  diagnostic surface (e.g., Settings → Diagnostics shows
     *  "N requests failed to decrypt" so users can detect a forged
     *  link campaign or a corrupted intro key). */
    private val _decryptFailures = MutableStateFlow(0)
    /** Request ids already counted, so a re-decode of the same
     *  undecodable row doesn't inflate the signal.
     *
     *  Concurrent by necessity: [decode] is reached both from the
     *  collector in [start] and from [consumeRequestAndSiblings] under
     *  [mutex], which [refresh] never takes — a plain LinkedHashSet is
     *  a ConcurrentModificationException waiting to happen, not just a
     *  lost count. */
    private val countedDecodeFailures: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Surviving row id → every raw id that collapsed into it. A
     *  StateFlow so the collector's write is visible to the caller. */
    private val collapsedIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    @Suppress("unused")
    val decryptFailures: StateFlow<Int> = _decryptFailures.asStateFlow()

    /**
     * Subscribe to [IntroRequestStore.requests] and keep [pending]
     * in sync. Idempotent — safe to call once at app start. The
     * collector lives for [scope]'s lifetime.
     *
     * Group snapshots are in the collection too, because a request is
     * decoded *against* its group: the name it shows and the rules
     * verdict it carries both come from a snapshot that may not have
     * hydrated yet. Requests restored from disk routinely emit before
     * the group store finishes loading, and without this the verdict
     * decided in that window — "this group has no rules", which is a
     * claim — would stand until some unrelated request happened to
     * arrive. Re-deciding costs one decrypt per stored request per
     * roster change, on a list bounded by outstanding joins.
     */
    override fun start() {
        scope.launch {
            combine(
                introRequestStore.requests,
                groupRepository.snapshots,
            ) { raw, _ -> raw }.collectLatest { raw -> refresh(raw) }
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
     * Collapse copies of one logical join — retries and replays each
     * carry a distinct id. Newest copy at the first-seen index.
     */
    private suspend fun refresh(raw: List<IntroRequest>) {
        val declined = introRequestStore.declinedCollapseKeys()
        val winners = LinkedHashMap<String, Pair<PendingRequest, IntroRequest>>()
        val siblings = LinkedHashMap<String, MutableList<String>>()
        for (r in raw) {
            val decoded = decode(r) ?: continue
            val key = collapseKey(decoded)
            // Declined joiners stay declined across retries and
            // relaunches; the link itself is unaffected.
            if (key in declined) continue
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
     * Build the [GroupInvitationPayload], seal, ship, drop the pending
     * entry. The key survives; an existing member skips the anchor.
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

        // Already in the roster (reinstall, replay, retry) — re-anchoring
        // would duplicate a leaf. `members`, not `memberProfiles`.
        val blsPub = req.joinerBlsPublicKey
        val alreadyInRoster = blsPub != null && group.members.any {
            it.publicKeyCompressed.contentEquals(blsPub)
        }

        // PR 88 admin-anchor leg — Tyranny only. Other governance
        // types fall through to the pre-PR-88 ship-only flow below.
        var anchored = group
        if (group.groupType == SepGroupType.TYRANNY && !alreadyInRoster) {
            when (val outcome = anchorTyrannyJoin(req, group)) {
                is AnchorOutcome.Failed -> {
                    // The approval failed, but the reconcile may still
                    // have learned where the chain actually is. Keeping
                    // that is what stops the next attempt paying for
                    // the same discovery.
                    outcome.reconciled?.let { settleAnchor(it) }
                    return@withLock outcome.outcome
                }
                is AnchorOutcome.Ok -> {
                    anchored = outcome.group
                    // Persist the advanced state immediately so a
                    // subsequent crash before seal+ship doesn't lose
                    // the chain transition.
                    settleAnchor(anchored)
                }
            }
        }

        // The directory the joiner lands with, including their own row.
        //
        // Their own row has to come from here, because it is the one
        // thing about themselves they cannot reconstruct: this device
        // holds the signature they sent, and their device kept no copy
        // of it. Without it they arrive to find themselves marked as
        // never having signed the rules they had just agreed to — on
        // the one screen where their agreement is the point. Found on
        // iOS by running the app; nothing below the UI crosses two
        // devices, so nothing else was going to catch it.
        val invitedProfiles = anchored.memberProfiles.toMutableMap()
        if (blsPub != null) {
            invitedProfiles[blsPub.toHexLowercase()] = joinerMemberProfile(
                alias = req.joinerDisplayLabel,
                inboxPub = req.joinerInboxPublicKey,
                sendingPub = req.joinerSendingPublicKey,
                rulesHash = req.rulesHash,
                rulesSignature = req.rulesSignature,
                groupRules = anchored.invitationMessage,
            )
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
            // peers + admin by name from the moment they land — plus
            // their own row, for the agreement they can't rebuild
            // themselves. The receiver still overwrites the alias and
            // keys in it with its own; see the materializer.
            memberProfiles = invitedProfiles.takeIf { it.isNotEmpty() },
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
            // Idempotent, and the point of the re-join path: a
            // reinstalled joiner has a new inbox pubkey.
            recordJoiner(
                group = anchored,
                blsPub = blsPub,
                inboxPub = req.joinerInboxPublicKey,
                sendingPub = req.joinerSendingPublicKey,
                alias = req.joinerDisplayLabel,
                rulesHash = req.rulesHash,
                rulesSignature = req.rulesSignature,
            )
            // Kept on re-join, unlike iOS: receivers dedup it free, and
            // it heals peers after an anchor that failed at seal+ship.
            broadcastJoin(
                group = anchored,
                joinerBlsPub = blsPub,
                joinerInboxPub = req.joinerInboxPublicKey,
                joinerSendingPub = req.joinerSendingPublicKey,
                joinerAlias = req.joinerDisplayLabel,
                // Fanned out with the rest of the profile: every member
                // holds `sendingPub`, so every member can check this.
                // Kept to the founder, the evidence would reach nobody
                // who joined earlier.
                rulesHash = req.rulesHash,
                rulesSignature = req.rulesSignature,
            )
            // The admin's own "X joined" row. Everyone else derives
            // theirs from the announcement fanned out just above, which
            // the admin — as its sender — never receives.
            systemEvents.recordMemberJoined(
                groupId = anchored.id,
                ownerIdentityId = anchored.ownerIdentityId,
                groupType = anchored.groupType,
                joinerBlsPubkeyHex = blsPub.toHexLowercase(),
                alias = req.joinerDisplayLabel,
                atMillis = System.currentTimeMillis(),
            )
        }

        // A create-time offer key is 1:1 with one named invitee and is
        // spent the moment that invitee is in. Leaving it live would
        // hold a relay subscription for good and leave a private link
        // redeemable by anyone it leaked to — retire it here rather
        // than hoping the host finds the invite list. The shared link
        // is untouched: that one is meant to be multi-use.
        revokeSpentOfferKey(requestId, anchored)

        // Drop the request and its siblings. The SHARED key stays
        // alive, or every other joiner's row would silently vanish.
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
        rulesHash: ByteArray?,
        rulesSignature: ByteArray?,
    ) {
        val key = blsPub.toHexLowercase()
        // This device's own copy of the rules, as of the approval — the
        // text the verdict was reached against, kept because re-reading
        // the decision later needs the words and not a pointer to
        // whatever the group says by then.
        //
        // Stored only when it is the text the joiner's own hash names.
        // Otherwise the member would carry our words beside a hash of
        // theirs, and anything reading the pair back would be checking a
        // signature against text it was never made over — a stored
        // triple that contradicts itself is worse than a missing field.
        // The hash and signature are kept either way: they are what
        // separates "signed something this device can't check" from
        // "signed nothing", which is the distinction the verdict exists
        // for.
        val updated = group.copy(
            memberProfiles = group.memberProfiles +
                (key to joinerMemberProfile(
                    alias = alias,
                    inboxPub = inboxPub,
                    sendingPub = sendingPub,
                    rulesHash = rulesHash,
                    rulesSignature = rulesSignature,
                    groupRules = group.invitationMessage,
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
        rulesHash: ByteArray?,
        rulesSignature: ByteArray?,
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
                    rulesHash = rulesHash,
                    rulesSignature = rulesSignature,
                    // Same predicate as everywhere else a text and a
                    // hash are put side by side — not a hash-only
                    // approximation of it. `AnnouncedMember`'s own
                    // `init` enforces the cap, so a hand-rolled check
                    // here would throw out of `approve()` *after* the
                    // invitation had already shipped.
                    rulesText = MemberProfile.storableRulesText(
                        GroupRules.normalized(group.invitationMessage),
                        rulesHash,
                    ),
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

    /** Drop that one request and its siblings, nothing else: a decline
     *  judges one requester, not the link. No NACK to the joiner. */
    /** Drop that request and its siblings, and remember the joiner. A
     *  decline judges one requester, not the link — the link stays live
     *  for everyone else — but it has to mean something: the joiner's
     *  screen offers a retry, and each retry is a fresh Nostr event, so
     *  an id-keyed tombstone would let a declined stranger refill the
     *  queue indefinitely. Keyed on the collapse key, which survives
     *  that. No NACK to the joiner. */
    override suspend fun decline(requestId: String): Unit = mutex.withLock {
        _pending.value.firstOrNull { it.id == requestId }?.let { target ->
            introRequestStore.recordDeclined(collapseKey(target))
        }
        consumeRequestAndSiblings(requestId)
    }

    // ─── private ──────────────────────────────────────────────────

    private suspend fun decode(raw: IntroRequest): PendingRequest? {
        // We hold the introPub on the IntroRequest from PR-3's
        // pump. Look up the privkey via IntroKeyStore. If the
        // privkey is missing (e.g., the entry was already revoked
        // before we got here), drop the request silently.
        val entry = introKeyStore.find(raw.targetIntroPublicKey)
        if (entry == null) {
            // Revoked or rotated away, or a pubkey this device never
            // minted. Counted like every other decode failure — now
            // that links are retired only deliberately, this is what a
            // request against a retired link looks like.
            //
            // Once per request id: [refresh] re-decodes the whole raw
            // set on every store emission and a request against a
            // retired link is never consumed, so a running total would
            // climb without bound off one dead link and be
            // indistinguishable from a thousand real replays.
            if (countedDecodeFailures.add(raw.id)) _decryptFailures.update { it + 1 }
            return null
        }

        val plaintext = try {
            IdentityRepository.decryptSealedEnvelopeWithKey(
                envelopeBytes = raw.payload,
                recipientX25519PrivateKey = entry.introPrivateKey,
            )
        } catch (e: InvitationDecryptError) {
            _decryptFailures.update { it + 1 }
            return null
        } catch (e: Throwable) {
            _decryptFailures.update { it + 1 }
            return null
        }
        val payload = try {
            jsonFormat.decodeFromString(JoinRequestPayload.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            _decryptFailures.update { it + 1 }
            return null
        } catch (_: IllegalArgumentException) {
            _decryptFailures.update { it + 1 }
            return null
        }
        if (!payload.groupId.contentEquals(entry.groupId)) {
            // Joiner is asking about a different group than the
            // intro entry was minted for. Forged or stale link —
            // drop silently.
            _decryptFailures.update { it + 1 }
            return null
        }

        val group = groupRepository.snapshots.value
            .firstOrNull { it.groupIdBytes.contentEquals(payload.groupId) }

        return PendingRequest(
            id = raw.id,
            joinerInboxPublicKey = payload.joinerInboxPublicKey,
            joinerBlsPublicKey = payload.joinerBlsPublicKey,
            joinerLeafHash = payload.joinerLeafHash,
            joinerSendingPublicKey = payload.joinerSendingPublicKey,
            joinerDisplayLabel = payload.joinerDisplayLabel,
            groupId = payload.groupId,
            groupName = group?.name,
            rulesAgreement = rulesAgreementFor(payload, group?.invitationMessage),
            rulesSignature = payload.rulesSignature,
            rulesHash = payload.rulesHash,
        )
    }

    /** Drop [requestId] plus every sibling that collapsed into its row;
     *  the winner alone would let one resurface. The key stays alive. */
    private suspend fun consumeRequestAndSiblings(requestId: String) {
        val ids = (collapsedIds.value[requestId] ?: listOf(requestId)).toMutableSet()
        // The cached map is only as fresh as the last [refresh], so
        // re-derive against the live store before consuming.
        _pending.value.firstOrNull { it.id == requestId }?.let { target ->
            val key = collapseKey(target)
            for (raw in introRequestStore.requests.value) {
                val decoded = decode(raw) ?: continue
                if (collapseKey(decoded) == key) ids += raw.id
            }
        }
        for (id in ids) introRequestStore.consume(id)
    }

    /**
     * Retire the per-invitee offer key this request actually arrived
     * on, if that is what it was.
     *
     * Matched on the link the request decoded against, NOT on the
     * joiner's fingerprint. Matching the label was wrong twice: a
     * reinstalled joiner presents a fresh inbox pubkey, so their offer
     * key would never be retired and would hold a private link and its
     * subscription open for good; and two invitees can share a 4-byte
     * fingerprint, so a label match would revoke a bystander's link
     * alongside the spent one. The intro pubkey is unambiguous.
     *
     * `label != null` is what distinguishes a create-time offer from
     * the group's shared link — the shared link is meant to be
     * multi-use and is never retired here.
     */
    private suspend fun revokeSpentOfferKey(requestId: String, group: ChatGroup) {
        val introPub = introRequestStore.requests.value
            .firstOrNull { it.id == requestId }?.targetIntroPublicKey ?: return
        val entry = introKeyStore.find(introPub) ?: return
        if (entry.label == null) return
        if (!entry.groupId.contentEquals(group.groupIdBytes)) return
        introKeyStore.revoke(introPub)
    }

    /** Same joiner, same group — what two copies of one join share. */
    private fun collapseKey(request: PendingRequest): String {
        val identity = request.joinerBlsPublicKey ?: request.joinerInboxPublicKey
        return identity.toHexLowercase() + ":" + request.groupId.toHexLowercase()
    }

    /** Outcome shape for [anchorTyrannyJoin]. */
    private sealed class AnchorOutcome {
        data class Ok(val group: ChatGroup) : AnchorOutcome()

        /**
         * [reconciled] is state the reconcile path learned from the
         * chain and the caller should persist even though the approval
         * failed — a landed transaction this device hadn't recorded.
         *
         * Carried out rather than written here because the anchor leg
         * stays pure: the caller owns persistence, and it is the caller
         * that knows to sweep the pending records afterwards. Dropping
         * it would make the next attempt rediscover the same thing, and
         * pay the chain read again to do it.
         */
        data class Failed(
            val outcome: ApproveOutcome,
            val reconciled: ChatGroup? = null,
        ) : AnchorOutcome()
    }

    /** Outcome shape for one [proveAndSubmitJoin] round. */
    private sealed class SubmitVerdict {
        data class Ok(val group: ChatGroup) : SubmitVerdict()

        /** The contract refused with `PUBLIC_INPUTS_MISMATCH` — the one
         *  refusal that says the fault is in what this device believes,
         *  and therefore the one worth reconciling. */
        object Stale : SubmitVerdict()

        data class Failed(val outcome: ApproveOutcome) : SubmitVerdict()
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

        // Resolve admin's index in the roster as it stands, for the
        // identity pre-flight below. Each attempt re-resolves its own.
        val adminIndexOld = adminIndexIn(group)
            ?: return AnchorOutcome.Failed(
                ApproveOutcome.TransportFailed("admin not in members roster"),
            )

        // The leaf this approval adds. Where it sorts into the roster
        // is decided per attempt, in [proveAndSubmitJoin].
        val joinerMember = GovernanceMember(
            publicKeyCompressed = joinerBlsPub,
            leafHash = joinerLeafHash,
        )

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

        val client = SepContractClient(
            contractID = binding.contractId,
            contractType = SepGroupType.TYRANNY,
            network = networkPref.sepNetwork,
            transport = makeContractTransport(relayerUrl),
        )

        return when (val first = proveAndSubmitJoin(client, group, blsSecret, joinerMember)) {
            is SubmitVerdict.Ok -> AnchorOutcome.Ok(first.group)
            is SubmitVerdict.Failed -> AnchorOutcome.Failed(first.outcome)
            // The contract held this proof's `c_old` up against the
            // commitment it actually stores and they differed. That is a
            // statement about *this device's* copy of the group, not
            // about the joiner — so ask the chain what it holds before
            // giving up on the approval.
            SubmitVerdict.Stale -> reconcileAndRetry(client, group, blsSecret, joinerMember)
        }
    }

    /**
     * Persist an advanced group, then drop the anchor records the
     * advance resolved.
     *
     * Strictly in that order. The records are the only evidence of
     * which transaction landed; sweeping them before the state they
     * explain is on disk would turn a crash in between into the exact
     * unrecoverable group this whole mechanism exists to prevent.
     *
     * Everything at or below the epoch just left is swept: the chain
     * has moved past it, so no proof from it can be accepted again and
     * nothing recorded against it can still be waiting to land. The
     * attempt that produced *this* state proved from that same epoch,
     * so it goes too.
     */
    private suspend fun settleAnchor(group: ChatGroup) {
        groupRepository.insert(group)
        if (group.epoch == 0uL) return
        runCatching {
            pendingAnchors.clear(
                groupId = group.groupIdBytes,
                ownerIdentityId = group.ownerIdentityId,
                throughEpoch = group.epoch - 1uL,
            )
        }
    }

    /** Where the group's admin sits in [group]'s roster, or null when
     *  it isn't there at all. */
    private fun adminIndexIn(group: ChatGroup): Int? {
        val adminPubkeyHex = group.adminPubkeyHex ?: return null
        val adminBytes = ChatGroup.bytesFromHex(adminPubkeyHex)
        return group.members
            .indexOfFirst { it.publicKeyCompressed.contentEquals(adminBytes) }
            .takeIf { it >= 0 }
    }

    /**
     * One prove-and-submit round from a given [group] state.
     *
     * Split out of [anchorTyrannyJoin] so the reconcile path can run it
     * a second time against a corrected state without duplicating the
     * proof wiring — and so "the chain refused because our old state
     * was wrong" is a value the caller can branch on rather than a
     * string in a message.
     */
    private suspend fun proveAndSubmitJoin(
        client: SepContractClient,
        group: ChatGroup,
        blsSecret: ByteArray,
        joinerMember: GovernanceMember,
    ): SubmitVerdict {
        // Resolved per attempt, against the roster actually being
        // proved from. A reconcile can change that roster between the
        // first attempt and the second, and an index carried over from
        // the first would name the wrong leaf.
        val adminIndexOld = adminIndexIn(group)
            ?: return SubmitVerdict.Failed(
                ApproveOutcome.TransportFailed("admin not in members roster"),
            )
        val newMembers = (group.members + joinerMember)
            .sortedWith(compareBy(byteArrayLexComparator()) { it.publicKeyCompressed })
        val memberRootNew = try {
            GroupCommitmentBuilder.computeMerkleRoot(
                members = newMembers,
                tier = group.tier,
            )
        } catch (e: Throwable) {
            return SubmitVerdict.Failed(ApproveOutcome.ProofFailed("merkle_root: ${e.message ?: e}"))
        }
        // Random, and it stays random: this is the blinding factor that
        // stops a chain observer confirming a guessed roster by
        // recomputing the commitment. Deriving it from anything other
        // members hold would hand that power to everyone who has ever
        // been in the group — including whoever was removed from it.
        // What makes it survivable is [pendingAnchors], below.
        val saltNew = GroupCommitmentBuilder.generateSalt()

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
            return SubmitVerdict.Failed(
                ApproveOutcome.ProofFailed(e.message ?: e.javaClass.simpleName),
            )
        } catch (e: Throwable) {
            return SubmitVerdict.Failed(
                ApproveOutcome.ProofFailed(e.message ?: e.toString()),
            )
        }

        val payload = TyrannyUpdateCommitmentPayload(
            groupId = group.groupIdBytes,
            proof = proof.proof,
            publicInputs = proof.publicInputs,
        )
        // Before the transaction goes out, not after. A row written
        // afterwards would miss exactly the window it exists to cover:
        // the process dying, or the answer never arriving, between the
        // submit and the write.
        //
        // A store that cannot keep the salt is a refusal, not a
        // warning. Submitting anyway would put the group one lost
        // response away from never accepting another member.
        try {
            pendingAnchors.record(
                PendingAnchor(
                    groupId = group.groupIdBytes,
                    ownerIdentityId = group.ownerIdentityId,
                    epochOld = group.epoch,
                    joinerPublicKey = joinerMember.publicKeyCompressed,
                    joinerLeafHash = joinerMember.leafHash,
                    saltNew = saltNew,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
        } catch (e: Throwable) {
            return SubmitVerdict.Failed(
                ApproveOutcome.TransportFailed(
                    "couldn't record the pending anchor: ${e.message ?: e}",
                ),
            )
        }

        val response = try {
            client.updateCommitmentTyranny(payload)
        } catch (e: SepContractError) {
            // A refused call arrives as a non-2xx whose body carries the
            // simulation output, so the contract's own error number is
            // in there rather than in a structured field.
            return when (e.contractErrorCode) {
                SepContractErrorCode.GROUP_NOT_FOUND.code ->
                    SubmitVerdict.Failed(ApproveOutcome.GroupNotAnchoredYet)
                SepContractErrorCode.PUBLIC_INPUTS_MISMATCH.code -> SubmitVerdict.Stale
                else -> SubmitVerdict.Failed(
                    ApproveOutcome.TransportFailed("anchor: ${e.message ?: e}"),
                )
            }
        } catch (e: Throwable) {
            return SubmitVerdict.Failed(
                ApproveOutcome.TransportFailed("anchor: ${e.message ?: e}"),
            )
        }
        if (!response.accepted) {
            // The same condition can also come back as a 200 with
            // `accepted: false`, depending on where the relayer catches
            // it — so both paths check.
            val message = response.message ?: "(no message)"
            return when (SepContractErrorCode.parse(message)) {
                SepContractErrorCode.GROUP_NOT_FOUND.code ->
                    SubmitVerdict.Failed(ApproveOutcome.GroupNotAnchoredYet)
                SepContractErrorCode.PUBLIC_INPUTS_MISMATCH.code -> SubmitVerdict.Stale
                else -> SubmitVerdict.Failed(ApproveOutcome.AnchorRejected(message))
            }
        }

        return SubmitVerdict.Ok(
            group.copy(
                members = newMembers,
                commitment = proof.commitmentNew,
                epoch = group.epoch + 1uL,
                salt = saltNew,
            ),
        )
    }

    /**
     * What to do after the contract answers `PUBLIC_INPUTS_MISMATCH`.
     *
     * The proof itself was fine; it proved a step *out of a state the
     * chain is not in*. Which is nearly always this: an earlier attempt
     * — at this same approval, or at another one from the same epoch —
     * reached the ledger and its answer did not reach the phone. A
     * relayer 502, a dropped connection, a kill. The chain advanced and
     * this device kept the state it had, so the founder sees the first
     * Accept fail and taps again, and the second tap is the one that
     * gets #10.
     *
     * [pendingAnchors] is what makes that answerable. Each attempt
     * wrote down the salt it was moving to, so the commitments those
     * attempts *would* have produced can be recomputed and held up
     * against what the chain actually holds. Whichever one matches is
     * the transaction that landed.
     *
     * Three shapes come out of that:
     *
     *  - this approval's own attempt landed. Nothing left to submit —
     *    adopt the state and let [approve] carry on to the invitation
     *    the joiner never got.
     *  - a *different* joiner's attempt landed, which is what has been
     *    blocking this one. Adopt that state and re-prove this join
     *    from it. The other joiner's request is still pending (their
     *    approval failed too), and re-approving them now takes
     *    [approve]'s already-in-roster path: invitation only, no second
     *    anchor.
     *  - nothing landed but the chain is at a later epoch over this
     *    exact roster and salt, so only the counter drifted. Re-prove
     *    from the chain's epoch.
     *
     * Anything else is a divergence this device cannot name, and a
     * second proof would be as wrong as the first. That is
     * [ApproveOutcome.StaleGroupState], and it is deliberately not a
     * retry. It is also where a group anchored by a build that kept no
     * pending record lands — there is nothing to recompute against.
     */
    private suspend fun reconcileAndRetry(
        client: SepContractClient,
        group: ChatGroup,
        blsSecret: ByteArray,
        joinerMember: GovernanceMember,
    ): AnchorOutcome {
        val entry = try {
            client.getCommitment(group.groupIdBytes)
        } catch (e: Throwable) {
            // Read-back failed, so the mismatch stays unexplained.
            // Reported as the chain refusal it was rather than as a
            // reconcile failure the founder never asked for.
            return AnchorOutcome.Failed(
                ApproveOutcome.AnchorRejected(
                    "the chain holds a different state for this group, " +
                        "and re-reading it failed: ${e.message ?: e}",
                ),
            )
        }

        val candidates = try {
            pendingAnchors.pending(group.groupIdBytes, group.ownerIdentityId)
        } catch (_: Throwable) {
            emptyList()
        }

        val adopted = adoptLandedAnchor(group, entry, candidates, commitmentOf)
        if (adopted != null) {
            if (adopted.joinerPublicKey.contentEquals(joinerMember.publicKeyCompressed)) {
                // Our own transaction. The chain already holds the join
                // this call was making.
                return AnchorOutcome.Ok(adopted.group)
            }
            // Someone else's. Re-prove this join from the corrected
            // state — and hand the correction back even if that fails,
            // so a second failure doesn't throw away what was learned
            // and leave the next attempt to rediscover it.
            return when (
                val retry = proveAndSubmitJoin(client, adopted.group, blsSecret, joinerMember)
            ) {
                is SubmitVerdict.Ok -> AnchorOutcome.Ok(retry.group)
                is SubmitVerdict.Failed ->
                    AnchorOutcome.Failed(retry.outcome, reconciled = adopted.group)
                SubmitVerdict.Stale -> AnchorOutcome.Failed(
                    ApproveOutcome.StaleGroupState(
                        localEpoch = adopted.group.epoch,
                        chainEpoch = entry.epoch,
                    ),
                    reconciled = adopted.group,
                )
            }
        }

        val rebased = rebaseOnChainEpoch(group, entry, commitmentOf)
            ?: return AnchorOutcome.Failed(
                ApproveOutcome.StaleGroupState(
                    localEpoch = group.epoch,
                    chainEpoch = entry.epoch,
                ),
            )

        return when (val second = proveAndSubmitJoin(client, rebased, blsSecret, joinerMember)) {
            is SubmitVerdict.Ok -> AnchorOutcome.Ok(second.group)
            is SubmitVerdict.Failed -> AnchorOutcome.Failed(second.outcome, reconciled = rebased)
            // Refused again against the chain's own epoch. One retry was
            // the offer; a loop here would just re-prove into the same
            // wall for 3-5 seconds a go.
            SubmitVerdict.Stale -> AnchorOutcome.Failed(
                ApproveOutcome.StaleGroupState(
                    localEpoch = group.epoch,
                    chainEpoch = entry.epoch,
                ),
                reconciled = rebased,
            )
        }
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

/**
 * Recomputes a group's on-chain commitment from
 * `(roster, tier, epoch, salt)`. Production is
 * [GroupCommitmentBuilder]; the reconcile tests pass a pure stand-in
 * so they need no JNI.
 */
internal typealias CommitmentRecomputing =
    (List<GovernanceMember>, SepTier, ULong, ByteArray) -> ByteArray

/** Lex comparator over [ByteArray]; matches the canonical member
 *  ordering already used in [CreateGroupInteractor]. */
internal fun byteArrayLexComparator(): Comparator<ByteArray> =
    Comparator { a, b ->
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return@Comparator cmp
        }
        a.size - b.size
    }

/** A recorded attempt the chain turns out to have accepted, and the
 *  state adopting it puts this device in. */
internal data class AdoptedAnchor(
    val group: ChatGroup,
    val joinerPublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is AdoptedAnchor &&
            group == other.group &&
            joinerPublicKey.contentEquals(other.joinerPublicKey))

    override fun hashCode(): Int = 31 * group.hashCode() + joinerPublicKey.contentHashCode()
}

/**
 * The recorded attempt that [entry] shows actually landed, if one did.
 *
 * Each [PendingAnchor] kept the salt its transaction was moving to, so
 * the commitment it would have produced is recomputable: roster plus
 * that joiner, at `epochOld + 1`, under that salt. Exactly one can
 * match what the chain holds — a commitment is binding, so a match is
 * an identification, not a guess.
 *
 * Checked by recomputing rather than by trusting the epoch counter. A
 * matching epoch over a different roster is a different group state,
 * and adopting it would put the founder's device into a belief the
 * chain does not share.
 *
 * `null` when the chain is somewhere none of the records explain —
 * including when there are no records, which is every group anchored
 * before they were kept.
 */
internal fun adoptLandedAnchor(
    group: ChatGroup,
    entry: SepCommitmentEntry,
    candidates: List<PendingAnchor>,
    commitmentOf: CommitmentRecomputing,
): AdoptedAnchor? {
    // Only a single step forward can be one of ours: every recorded
    // attempt proved from an epoch, and an accepted proof advances the
    // chain by exactly one.
    if (entry.epoch != group.epoch + 1uL) return null

    for (candidate in candidates) {
        if (candidate.epochOld != group.epoch) continue
        // Already in the roster — this record was resolved and merely
        // outlived its sweep. Adding the leaf twice would build a tree
        // the contract never committed to.
        if (group.members.any { it.publicKeyCompressed.contentEquals(candidate.joinerPublicKey) }) {
            continue
        }
        val newMembers = (
            group.members + GovernanceMember(
                publicKeyCompressed = candidate.joinerPublicKey,
                leafHash = candidate.joinerLeafHash,
            )
            ).sortedWith(compareBy(byteArrayLexComparator()) { it.publicKeyCompressed })
        val expected = try {
            commitmentOf(newMembers, group.tier, entry.epoch, candidate.saltNew)
        } catch (_: Throwable) {
            continue
        }
        if (!expected.contentEquals(entry.commitment)) continue
        return AdoptedAnchor(
            group = group.copy(
                members = newMembers,
                commitment = entry.commitment,
                epoch = entry.epoch,
                salt = candidate.saltNew,
            ),
            joinerPublicKey = candidate.joinerPublicKey,
        )
    }
    return null
}

/**
 * `group` moved onto the chain's epoch, if the chain holds this exact
 * roster and salt and only the counter drifted — the shape a
 * half-persisted or replayed update leaves behind.
 *
 * `null` when the epochs already agree (the mismatch was something
 * else, and re-proving would spend 3-5 seconds to be refused
 * identically) or when the chain's commitment isn't over state this
 * device can reproduce.
 */
internal fun rebaseOnChainEpoch(
    group: ChatGroup,
    entry: SepCommitmentEntry,
    commitmentOf: CommitmentRecomputing,
): ChatGroup? {
    if (entry.epoch == group.epoch) return null
    val expected = try {
        commitmentOf(group.members, group.tier, entry.epoch, group.salt)
    } catch (_: Throwable) {
        return null
    }
    if (!expected.contentEquals(entry.commitment)) return null
    return group.copy(commitment = entry.commitment, epoch = entry.epoch)
}

/**
 * Decide the agreement once, here, where the group's own rules are
 * in hand — rather than handing a signature to a screen and asking
 * it to work out what to make of it.
 */
internal fun rulesAgreementFor(
    payload: JoinRequestPayload,
    rules: String?,
): JoinRequestApprover.RulesAgreement {
    val ours = GroupRules.normalized(rules) ?: return JoinRequestApprover.RulesAgreement.NOT_REQUIRED
    val signature = payload.rulesSignature ?: return JoinRequestApprover.RulesAgreement.NOT_SIGNED
    val signedHash = payload.rulesHash ?: return JoinRequestApprover.RulesAgreement.NOT_SIGNED
    // Checked against the group's text before the hash is compared,
    // so a joiner can't pass by echoing back the right hash with a
    // signature over something else: verification uses *our* rules,
    // and the hash they sent only ever explains a failure.
    if (GroupRules.isAgreement(
            signature = signature,
            rules = ours,
            groupId = payload.groupId,
            joinerSendingPublicKey = payload.joinerSendingPublicKey,
        )
    ) {
        return JoinRequestApprover.RulesAgreement.AGREED
    }
    // Claimed our exact rules and failed against them: not an old
    // client, not another version, and nothing else this can be.
    // Claimed some other text, and we have no copy of it to check
    // against — so we say that, rather than calling it agreement.
    return if (signedHash.contentEquals(GroupRules.hash(ours))) {
        JoinRequestApprover.RulesAgreement.INVALID
    } else {
        JoinRequestApprover.RulesAgreement.UNKNOWN_RULES
    }
}

/**
 * The row a joiner becomes, derived in one place.
 *
 * Used for the invitation shipped to them and for the record kept
 * locally, because the receiver's merge assumes the two agree — and
 * deriving them separately twice made that agreement incidental rather
 * than structural.
 *
 * The wording is paired with the signature only when the hash names it:
 * pairing it with whatever the group says *now* made a founder's edit
 * between request and approval land on the joiner as "their signature
 * doesn't check out" — red, about themselves, on their first look at
 * the group.
 */
internal fun joinerMemberProfile(
    alias: String,
    inboxPub: ByteArray,
    sendingPub: ByteArray,
    rulesHash: ByteArray?,
    rulesSignature: ByteArray?,
    groupRules: String?,
): MemberProfile = MemberProfile(
    alias = alias,
    inboxPublicKey = inboxPub,
    sendingPubkey = sendingPub,
    // Recorded whatever the verdict said, including when it didn't
    // verify. The founder saw it and approved anyway; keeping the bytes
    // is what lets that decision be re-examined, and dropping them
    // would make "approved someone who signed nothing" and "approved
    // someone whose signature was wrong" indistinguishable afterwards.
    rulesHash = rulesHash,
    rulesSignature = rulesSignature,
    rulesText = MemberProfile.storableRulesText(
        GroupRules.normalized(groupRules),
        rulesHash,
    ),
)
