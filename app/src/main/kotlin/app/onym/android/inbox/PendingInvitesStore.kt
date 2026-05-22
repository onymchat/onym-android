package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * One decoded [app.onym.android.group.GroupInviteOfferPayload] awaiting
 * the user's explicit Accept / Dismiss. Unlike the opaque ciphertext
 * the legacy invitations queue holds, the offer is decrypted + decoded
 * at receive time by [IncomingMessageDispatcher], so this record
 * carries the structured fields the UI needs to render "X invited you
 * to Y" and the intro public key the Accept action replies to.
 *
 * Mirrors `PendingInvite` from onym-ios PR #158.
 */
data class PendingInvite(
    /** Nostr event id of the inbound offer — the dedupe + consume key. */
    val id: String,
    /** Identity the offer was delivered to (the inbox tag it arrived
     *  on). The Accept action replies *as* this identity. */
    val ownerIdentityId: IdentityId,
    /** Admin's per-invite intro pubkey — the reply channel the join
     *  request is sealed to. */
    val introPublicKey: ByteArray,
    val groupId: ByteArray,
    val groupName: String?,
    val inviterAlias: String,
    val receivedAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingInvite) return false
        return id == other.id &&
            ownerIdentityId == other.ownerIdentityId &&
            introPublicKey.contentEquals(other.introPublicKey) &&
            groupId.contentEquals(other.groupId) &&
            groupName == other.groupName &&
            inviterAlias == other.inviterAlias &&
            receivedAt == other.receivedAt
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + introPublicKey.contentHashCode()
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + (groupName?.hashCode() ?: 0)
        h = 31 * h + inviterAlias.hashCode()
        h = 31 * h + receivedAt.hashCode()
        return h
    }
}

/**
 * Receive-side seam the dispatcher writes decoded offers into. Kept
 * minimal (one method) so the dispatcher depends on a narrow interface
 * rather than the concrete store — and existing dispatcher tests that
 * don't exercise the offer path can pass a fresh default store.
 *
 * Mirrors `PendingInvitesRecording` from onym-ios PR #158.
 */
interface PendingInvitesRecording {
    /** Idempotent on [PendingInvite.id]. Re-delivery of the same offer
     *  (replaceable Nostr event re-fetched on relaunch) is a no-op. */
    suspend fun record(invite: PendingInvite)
}

/**
 * Process-lifetime store of pending invites, filtered by the
 * currently-selected identity. In-memory by design: the offer itself
 * is a retained Nostr event, so the inbox fan-out re-delivers it on
 * every launch — exactly like [app.onym.android.group.InMemoryIntroRequestStore]
 * for join requests. [snapshots] mirrors the per-identity filtering the
 * group / invitations repositories use, so the list flips when the
 * user switches identity.
 *
 * Android analogue of the iOS `PendingInvitesStore` actor — a [Mutex]
 * guards the mutable list and a [StateFlow] publishes the filtered
 * snapshot.
 */
class PendingInvitesStore : PendingInvitesRecording {
    private val mutex = Mutex()
    private val all = mutableListOf<PendingInvite>()
    private var currentIdentity: IdentityId? = null

    private val _snapshots = MutableStateFlow<List<PendingInvite>>(emptyList())

    /** Hot stream of pending invites for the current identity, newest
     *  first. Empty until an identity is selected via
     *  [setCurrentIdentity]. */
    val snapshots: StateFlow<List<PendingInvite>> = _snapshots.asStateFlow()

    override suspend fun record(invite: PendingInvite) = mutex.withLock {
        if (all.any { it.id == invite.id }) return@withLock
        all.add(invite)
        publishLocked()
    }

    /** Drop an invite after the user accepted or dismissed it. */
    suspend fun consume(id: String) = mutex.withLock {
        if (all.removeAll { it.id == id }) publishLocked()
    }

    /** Cascade for the identity-removal flow. */
    suspend fun removeForOwner(id: IdentityId) = mutex.withLock {
        if (all.removeAll { it.ownerIdentityId == id }) publishLocked()
    }

    /**
     * Drop every pending invite whose group now exists locally — the
     * admin approved + the [app.onym.android.group.GroupInvitationPayload]
     * materialized the group, so the offer has served its purpose.
     * Called by the view-model when [app.onym.android.group.GroupRepository]
     * emits.
     */
    suspend fun consumeForMaterializedGroups(groupIds: Set<List<Byte>>) = mutex.withLock {
        if (groupIds.isEmpty()) return@withLock
        if (all.removeAll { groupIds.contains(it.groupId.toList()) }) publishLocked()
    }

    suspend fun setCurrentIdentity(id: IdentityId?) = mutex.withLock {
        currentIdentity = id
        publishLocked()
    }

    private fun publishLocked() {
        val active = currentIdentity
        _snapshots.value = if (active == null) {
            emptyList()
        } else {
            all.filter { it.ownerIdentityId == active }
                .sortedByDescending { it.receivedAt }
        }
    }
}
