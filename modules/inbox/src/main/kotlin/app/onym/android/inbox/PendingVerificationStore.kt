package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * A Tyranny group whose invitation snapshot couldn't be verified at an
 * exact epoch (the chain had advanced past it), so it's awaiting a
 * fresh snapshot from the admin before it may materialize. Kept OUT of
 * the chats list — an unverifiable snapshot must never look like a real
 * chat — and surfaced in the Invitations UI so the user knows a join is
 * in flight (or stuck because the admin is offline).
 *
 * Mirrors `PendingGroupVerification` from onym-ios PR #159.
 */
data class PendingGroupVerification(
    /** Dedupe key — one pending verification per group. Lowercase hex of
     *  the 32-byte on-chain `group_id` (matches [app.onym.android.group.ChatGroup.id]). */
    val groupIdHex: String,
    val ownerIdentityId: IdentityId,
    val groupName: String,
    val status: Status,
    val receivedAt: Instant,
) {
    val id: String get() = groupIdHex

    enum class Status {
        /** Refresh request sent; waiting for the admin's reply. */
        VERIFYING,

        /** No reply within the timeout (or no admin inbox to ask) —
         *  surfaced to the user with a Retry. */
        UNREACHABLE,
    }
}

/**
 * In-memory, per-identity-filtered store of groups awaiting
 * verification. In-memory by design: the stale invitation is a retained
 * Nostr event re-delivered on every launch, so the verifier re-defers
 * and re-requests on relaunch — same model as [PendingInvitesStore].
 *
 * Android analogue of the iOS `PendingVerificationStore` actor — a
 * [Mutex] guards the list and a [StateFlow] publishes the filtered
 * snapshot.
 */
class PendingVerificationStore {
    private val mutex = Mutex()
    private val all = mutableListOf<PendingGroupVerification>()
    private var currentIdentity: IdentityId? = null

    private val _snapshots = MutableStateFlow<List<PendingGroupVerification>>(emptyList())
    val snapshots: StateFlow<List<PendingGroupVerification>> = _snapshots.asStateFlow()

    /** Idempotent on [PendingGroupVerification.groupIdHex]. A re-deferred
     *  snapshot (re-delivery) keeps the existing entry/status rather than
     *  resetting it. */
    suspend fun record(entry: PendingGroupVerification) = mutex.withLock {
        if (all.any { it.groupIdHex == entry.groupIdHex }) return@withLock
        all.add(entry)
        publishLocked()
    }

    suspend fun updateStatus(groupIdHex: String, status: PendingGroupVerification.Status) =
        mutex.withLock {
            val idx = all.indexOfFirst { it.groupIdHex == groupIdHex }
            if (idx < 0 || all[idx].status == status) return@withLock
            all[idx] = all[idx].copy(status = status)
            publishLocked()
        }

    suspend fun contains(groupIdHex: String): Boolean = mutex.withLock {
        all.any { it.groupIdHex == groupIdHex }
    }

    /** Remove entries whose group now exists locally — the fresh
     *  snapshot verified + materialized, so verification is done. */
    suspend fun resolveMaterialized(groupIdHexes: Set<String>) = mutex.withLock {
        if (groupIdHexes.isEmpty()) return@withLock
        if (all.removeAll { groupIdHexes.contains(it.groupIdHex) }) publishLocked()
    }

    suspend fun removeForOwner(id: IdentityId) = mutex.withLock {
        if (all.removeAll { it.ownerIdentityId == id }) publishLocked()
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
