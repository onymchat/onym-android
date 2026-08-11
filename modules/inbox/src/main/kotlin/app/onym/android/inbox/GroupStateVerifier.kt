package app.onym.android.inbox

import app.onym.android.group.GroupInvitationPayload
import app.onym.android.group.GroupRepository
import app.onym.android.group.GroupStateRefreshRequest
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Seam the dispatcher delegates Tyranny group-state verification to.
 * Two roles, because any device is both a potential invitee and a
 * potential admin:
 *   - [deferVerification] (invitee): a snapshot couldn't be verified at
 *     an exact epoch (chain advanced past it) — ask the admin for the
 *     current state instead of materializing an unverifiable group.
 *   - [handleRefreshRequest] (admin): reply to such a request with the
 *     current snapshot, gated on the requester being a current member.
 *
 * Mirrors `GroupStateRefreshing` from onym-ios PR #159.
 */
interface GroupStateRefreshing {
    suspend fun deferVerification(invitation: GroupInvitationPayload, ownerIdentityId: IdentityId)

    /**
     * Park a snapshot that failed verification for a reason the admin
     * cannot fix — this device couldn't read the chain, or the group's
     * anchoring hasn't settled. Records the pending group so the user
     * can see it and retry, but sends nothing to the admin: a refresh
     * request would be answered promptly and change nothing.
     */
    suspend fun deferLocally(
        invitation: GroupInvitationPayload,
        ownerIdentityId: IdentityId,
        senderEd25519PublicKey: ByteArray?,
        status: PendingGroupVerification.Status,
    )
    suspend fun handleRefreshRequest(
        request: GroupStateRefreshRequest,
        ownerIdentityId: IdentityId,
        requesterEd25519: ByteArray?,
    )
}

/** No-op conformer — the dispatcher's default so existing dispatcher
 *  tests that don't exercise verification keep their construction sites
 *  unchanged. Mirrors `NoopGroupStateRefresher`. */
internal class NoopGroupStateRefresher : GroupStateRefreshing {
    override suspend fun deferVerification(invitation: GroupInvitationPayload, ownerIdentityId: IdentityId) {}
    override suspend fun deferLocally(
        invitation: GroupInvitationPayload,
        ownerIdentityId: IdentityId,
        senderEd25519PublicKey: ByteArray?,
        status: PendingGroupVerification.Status,
    ) {}
    override suspend fun handleRefreshRequest(
        request: GroupStateRefreshRequest,
        ownerIdentityId: IdentityId,
        requesterEd25519: ByteArray?,
    ) {}
}

/**
 * Drives the "verify at current state" leg of the converge-forward
 * design (Option 2). When an invitation snapshot is stale, the invitee
 * asks the admin for the current `(epoch, salt, members, commitment)`;
 * the admin replies with a fresh [GroupInvitationPayload] that verifies
 * at an exact epoch. If the admin can't be reached, the group is left
 * in an [PendingGroupVerification.Status.UNREACHABLE] pending state and
 * surfaced to the user — never silently materialized or dropped.
 *
 * Depends on narrow seams ([InvitationEnvelopeSealer], the identities +
 * active-id flows) rather than the concrete [IdentityRepository] so it's
 * exercisable on the JVM unit-test path.
 *
 * Mirrors the `GroupStateVerifier` actor from onym-ios PR #159 — a
 * [Mutex] stands in for the actor's isolation.
 */
class GroupStateVerifier(
    private val sealer: InvitationEnvelopeSealer,
    private val inboxTransport: InboxTransport,
    private val groupRepository: GroupRepository,
    private val store: PendingVerificationStore,
    /** All local identities; the invitee-side refresh is built from the
     *  active one's summary (V1 single-active assumption). */
    private val identities: StateFlow<List<IdentitySummary>>,
    private val activeIdentityId: StateFlow<IdentityId?>,
    /** Scope owning the per-group timeout tasks + the resolve watcher. */
    private val scope: CoroutineScope,
    private val refreshTimeoutMillis: Long = 30_000,
) : GroupStateRefreshing {

    private data class RefreshTarget(
        val groupId: ByteArray,
        val adminInboxKey: ByteArray,
        val ownerIdentityId: IdentityId,
    )

    private val mutex = Mutex()
    private val targets = mutableMapOf<String, RefreshTarget>()
    private val timeouts = mutableMapOf<String, Job>()
    private var watchJob: Job? = null
    /** Snapshots parked for a reason no admin can fix, kept so Retry can
     *  re-run verification against the chain. Same in-memory lifetime as
     *  everything else here: the snapshot is a retained relay event and
     *  comes back on the next launch anyway. */
    private val localDeferrals = mutableMapOf<String, LocalDeferral>()
    /** Auto-rechecks spent per group, so a chain that never settles
     *  polls a bounded number of times instead of forever. */
    private val recheckAttempts = mutableMapOf<String, Int>()
    /** Re-runs receive-side verification for a parked snapshot. Set by
     *  the app after the dispatcher exists — the dispatcher owns
     *  verification and holds this verifier, so a closure keeps the
     *  cycle out of initialization. */
    private var reverify: (suspend (GroupInvitationPayload, IdentityId, ByteArray?) -> Unit)? = null

    private data class LocalDeferral(
        val invitation: GroupInvitationPayload,
        val ownerIdentityId: IdentityId,
        val senderEd25519PublicKey: ByteArray?,
    )

    fun setReverify(block: suspend (GroupInvitationPayload, IdentityId, ByteArray?) -> Unit) {
        reverify = block
    }

    /**
     * Watch the group repo so a pending verification is resolved (and
     * its timeout cancelled) the moment the fresh snapshot materializes
     * its group. Idempotent.
     */
    fun start() {
        watchJob?.cancel()
        watchJob = scope.launch {
            groupRepository.snapshots.collect { groups ->
                resolve(groups.mapTo(mutableSetOf()) { it.id })
            }
        }
    }

    // ─── Invitee side ────────────────────────────────────────────────

    override suspend fun deferVerification(
        invitation: GroupInvitationPayload,
        ownerIdentityId: IdentityId,
    ) {
        val groupIdHex = invitation.groupId.toHexLowercase()
        // A snapshot already parked locally MUST be allowed through
        // here. The sequence is real: offline at launch parks it
        // CHAIN_UNREACHABLE, then the device comes back and the chain
        // has moved past the 64-entry archive window, so the admin
        // genuinely is the only source left. Early-returning on
        // `contains` left the card saying "check your connection"
        // forever, with a Retry that only re-read the chain. Only an
        // outstanding request (VERIFYING) is a reason to skip.
        if (store.status(groupIdHex) == PendingGroupVerification.Status.VERIFYING) return
        // Escalating: this is no longer ours to retry locally.
        mutex.withLock {
            localDeferrals.remove(groupIdHex)
            recheckAttempts.remove(groupIdHex)
        }

        // We can only ask the admin if the snapshot told us their inbox.
        val adminInbox = invitation.adminPubkeyHex?.lowercase()
            ?.let { invitation.memberProfiles?.get(it)?.inboxPublicKey }
        if (adminInbox == null) {
            store.record(
                PendingGroupVerification(
                    groupIdHex = groupIdHex,
                    ownerIdentityId = ownerIdentityId,
                    groupName = invitation.name,
                    status = PendingGroupVerification.Status.UNREACHABLE,
                    receivedAt = Instant.now(),
                ),
            )
            return
        }

        // `record` is idempotent on groupIdHex, so an entry parked
        // earlier (locally, for a reason the admin couldn't fix) would
        // keep its old status and the card would go on naming the wrong
        // party. Update it explicitly for that case.
        store.record(
            PendingGroupVerification(
                groupIdHex = groupIdHex,
                ownerIdentityId = ownerIdentityId,
                groupName = invitation.name,
                status = PendingGroupVerification.Status.VERIFYING,
                receivedAt = Instant.now(),
            ),
        )
        store.updateStatus(groupIdHex, PendingGroupVerification.Status.VERIFYING)
        mutex.withLock {
            targets[groupIdHex] = RefreshTarget(
                groupId = invitation.groupId.copyOf(),
                adminInboxKey = adminInbox.copyOf(),
                ownerIdentityId = ownerIdentityId,
            )
        }
        sendRefresh(groupIdHex)
    }

    /** Re-send a refresh for a still-pending group (manual Retry). No-op
     *  if the group resolved or we have no target for it. */
    suspend fun retry(groupIdHex: String) {
        // A locally-parked snapshot has nobody to ask: retrying it means
        // reading the chain again, which is exactly what re-running
        // verification does.
        val parked = mutex.withLock { localDeferrals[groupIdHex] }
        if (parked != null) {
            reverify?.invoke(
                parked.invitation,
                parked.ownerIdentityId,
                parked.senderEd25519PublicKey,
            )
            return
        }
        val hasTarget = mutex.withLock { targets.containsKey(groupIdHex) }
        if (!hasTarget) return
        store.updateStatus(groupIdHex, PendingGroupVerification.Status.VERIFYING)
        sendRefresh(groupIdHex)
    }

    override suspend fun deferLocally(
        invitation: GroupInvitationPayload,
        ownerIdentityId: IdentityId,
        senderEd25519PublicKey: ByteArray?,
        status: PendingGroupVerification.Status,
    ) {
        val groupIdHex = invitation.groupId.toHexLowercase()
        val known = mutex.withLock {
            localDeferrals[groupIdHex] = LocalDeferral(
                invitation, ownerIdentityId, senderEd25519PublicKey,
            )
            store.contains(groupIdHex)
        }
        if (known) {
            // Already parked. Refresh the reason so a group that moved
            // from "settling" to "can't read the chain" says so.
            store.updateStatus(groupIdHex, status)
            return
        }
        store.record(
            PendingGroupVerification(
                groupIdHex = groupIdHex,
                ownerIdentityId = ownerIdentityId,
                groupName = invitation.name,
                status = status,
                receivedAt = java.time.Instant.now(),
            ),
        )
        // No `targets` entry and no send: there is nobody to ask. The
        // card offers Retry, and a settling group additionally
        // re-reads on its own — the flagship case is an admin approving
        // seconds after `create_group`, where the fix is purely the
        // passage of time and asking the user to tap would be noise.
        if (status == PendingGroupVerification.Status.CHAIN_SETTLING) {
            scheduleRecheck(groupIdHex)
        }
    }

    /**
     * Re-read the chain for a locally-parked snapshot after a short
     * delay, up to [MAX_AUTO_RECHECKS] times.
     *
     * Bounded on purpose: each attempt that still fails re-parks the
     * snapshot, which would otherwise schedule another and poll the
     * relayer forever for a group that is never coming. Once the budget
     * is spent the card still has its Retry.
     */
    private suspend fun scheduleRecheck(groupIdHex: String) {
        val parked = mutex.withLock {
            val spent = recheckAttempts[groupIdHex] ?: 0
            if (spent >= MAX_AUTO_RECHECKS) return
            recheckAttempts[groupIdHex] = spent + 1
            timeouts[groupIdHex]?.cancel()
            localDeferrals[groupIdHex]
        } ?: return
        val job = scope.launch {
            delay(RECHECK_DELAY_MILLIS)
            reverify?.invoke(
                parked.invitation,
                parked.ownerIdentityId,
                parked.senderEd25519PublicKey,
            )
        }
        mutex.withLock { timeouts[groupIdHex] = job }
    }

    private suspend fun sendRefresh(groupIdHex: String) {
        val target = mutex.withLock { targets[groupIdHex] } ?: return
        // Build the request from the *active* identity. V1 assumes the
        // owner is the selected identity (matching the single-active
        // assumption elsewhere); if it isn't, the admin's membership
        // check simply won't match and we fall through to UNREACHABLE.
        val me = identities.value.firstOrNull { it.id == activeIdentityId.value }
        val request = me?.let {
            runCatching {
                GroupStateRefreshRequest(
                    groupId = target.groupId,
                    requesterInboxPublicKey = it.inboxPublicKey,
                    requesterBlsPublicKey = it.blsPublicKey,
                )
            }.getOrNull()
        }
        if (request == null) {
            store.updateStatus(groupIdHex, PendingGroupVerification.Status.UNREACHABLE)
            return
        }
        val sealed = runCatching {
            sealer.sealInvitation(
                jsonFormat.encodeToString(GroupStateRefreshRequest.serializer(), request)
                    .toByteArray(Charsets.UTF_8),
                target.adminInboxKey,
            )
        }.getOrNull()
        if (sealed == null) {
            store.updateStatus(groupIdHex, PendingGroupVerification.Status.UNREACHABLE)
            return
        }
        val tag = TransportInboxId(IdentityRepository.inboxTag(target.adminInboxKey))
        val receipt = runCatching { inboxTransport.send(sealed, tag) }.getOrNull()
        if (receipt == null || receipt.acceptedBy < 1) {
            store.updateStatus(groupIdHex, PendingGroupVerification.Status.UNREACHABLE)
            return
        }
        scheduleTimeout(groupIdHex)
    }

    private suspend fun scheduleTimeout(groupIdHex: String) = mutex.withLock {
        timeouts[groupIdHex]?.cancel()
        timeouts[groupIdHex] = scope.launch {
            delay(refreshTimeoutMillis)  // throws on cancel → marker won't run
            markUnreachableIfStillVerifying(groupIdHex)
        }
    }

    private suspend fun markUnreachableIfStillVerifying(groupIdHex: String) {
        if (!store.contains(groupIdHex)) return
        store.updateStatus(groupIdHex, PendingGroupVerification.Status.UNREACHABLE)
    }

    private suspend fun resolve(materializedHexes: Set<String>) {
        mutex.withLock {
            val resolved = (targets.keys + localDeferrals.keys)
                .filter { materializedHexes.contains(it) }
                .toSet()
            for (hex in resolved) {
                timeouts[hex]?.cancel()
                timeouts.remove(hex)
                targets.remove(hex)
                // Verified on a later pass — drop the retained snapshot
                // so a group secret doesn't outlive the reason it was
                // kept.
                localDeferrals.remove(hex)
                recheckAttempts.remove(hex)
            }
        }
        store.resolveMaterialized(materializedHexes)
    }

    // ─── Admin side ──────────────────────────────────────────────────

    override suspend fun handleRefreshRequest(
        request: GroupStateRefreshRequest,
        ownerIdentityId: IdentityId,
        requesterEd25519: ByteArray?,
    ) {
        val groupIdHex = request.groupId.toHexLowercase()
        val group = groupRepository.findForOwner(ownerIdentityId.value, groupIdHex) ?: return

        // Membership gate — the reply carries `salt`, so only answer a
        // requester that is a current member, and only after confirming
        // the envelope's signer matches that member's stored Ed25519
        // (insider-spoof defense). The sealing target is pinned to the
        // member's *stored* inbox key, not the request's claimed one, so
        // a forged request can't redirect the salt elsewhere.
        val key = request.requesterBlsPublicKey.toHexLowercase()
        val profile = group.memberProfiles[key] ?: return
        if (requesterEd25519 == null || !requesterEd25519.contentEquals(profile.sendingPubkey)) return
        if (!request.requesterInboxPublicKey.contentEquals(profile.inboxPublicKey)) return

        val invite = GroupInvitationPayload(
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
            memberProfiles = group.memberProfiles.takeIf { it.isNotEmpty() },
        )
        val sealed = runCatching {
            sealer.sealInvitation(
                jsonFormat.encodeToString(GroupInvitationPayload.serializer(), invite)
                    .toByteArray(Charsets.UTF_8),
                profile.inboxPublicKey,
            )
        }.getOrNull() ?: return
        val tag = TransportInboxId(IdentityRepository.inboxTag(profile.inboxPublicKey))
        runCatching { inboxTransport.send(sealed, tag) }
    }

    private companion object {
        /** A settling chain is seconds away, not minutes. */
        const val RECHECK_DELAY_MILLIS = 5_000L
        const val MAX_AUTO_RECHECKS = 3

        private val jsonFormat = Json { encodeDefaults = true }

        private fun ByteArray.toHexLowercase(): String =
            buildString(size * 2) { for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF)) }
    }
}
