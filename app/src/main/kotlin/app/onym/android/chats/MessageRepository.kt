package app.onym.android.chats

import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Owns the per-group view of chat messages and the corresponding
 * writes to the persistence seam. Compose / view-models subscribe
 * to one group's [snapshots] flow and re-render on every emission.
 *
 * **Per-group lazy cache.** Unlike
 * [app.onym.android.group.GroupRepository] (which loads the entire
 * groups list into one [StateFlow] at start time), messages are
 * read on demand — the chat thread UI subscribes to one group at a
 * time, so hydrating the full table at launch would be wasted I/O.
 * The first [snapshots] call for a `groupId` hits the store; later
 * calls share the cached flow.
 *
 * Cascade semantics:
 *  - **Identity removal** ([IdentityRepository.registerRemovalListener])
 *    deletes rows for that owner from the store and refreshes every
 *    cached group (drop the per-group flow's contents to empty
 *    *if and only if* the active identity matches; other-identity
 *    rows in the same group are not modified here).
 *  - **Group deletion** ([removeForGroup]) drops the cached flow for
 *    that group after deleting from the store.
 *  - **Identity switch** wipes the in-memory cache entirely; the
 *    new identity's groups refill on demand.
 *
 * iOS uses `actor MessageRepository`; Android uses `Mutex +
 * StateFlow` so Compose can `collectAsStateWithLifecycle()` the
 * per-group stream directly without per-view ceremony. Same per-
 * group lazy-cache semantics as iOS PR #148.
 *
 * Mirrors `MessageRepository.swift` from onym-ios PR #148.
 */
class MessageRepository(
    private val store: MessageStore,
    private val identity: ActiveIdentityProvider,
    /** Scope owning the active-identity collector. Pass an
     *  `applicationScope` (SupervisorJob + Dispatchers.IO). */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val perGroup = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    init {
        identity.registerRemovalListener { id ->
            mutex.withLock { handleOwnerRemovalLocked(id) }
        }
    }

    /**
     * Start observing the active-identity flow. Idempotent — safe
     * to call from `onCreate`. On every identity change (including
     * → null), wipes the cached per-group flows. The new identity's
     * groups refill on demand.
     */
    fun start() {
        scope.launch {
            identity.currentIdentityId.collectLatest {
                mutex.withLock { perGroup.clear() }
            }
        }
    }

    /**
     * Hot per-group stream. First call for a `groupId` hydrates
     * from the store under the currently-active identity; later
     * calls return the cached flow (multiple subscribers share one).
     * If no identity is selected, the returned flow stays empty
     * and emits the group's rows once an identity is selected and
     * [snapshots] is re-requested.
     */
    suspend fun snapshots(groupId: String): StateFlow<List<ChatMessage>> = mutex.withLock {
        val cached = perGroup[groupId]
        if (cached != null) return@withLock cached.asStateFlow()
        val flow = MutableStateFlow<List<ChatMessage>>(emptyList())
        perGroup[groupId] = flow
        val ownerId = identity.currentIdentityId.value
        if (ownerId != null) {
            flow.value = store.listForGroup(ownerId.value, groupId)
        }
        flow.asStateFlow()
    }

    /**
     * Persist [message] and emit on the corresponding group's
     * cached flow. Idempotent on [ChatMessage.id] — a re-delivery
     * (same wire `messageId`) is a no-op: returns `false`, skips the
     * cache refresh, no subscriber re-emission. Returns `true` on a
     * fresh insert.
     *
     * Cache refresh is conditional so a duplicate-arrival storm
     * doesn't spam every chat-thread subscriber with redundant
     * StateFlow emissions.
     */
    suspend fun append(message: ChatMessage): Boolean = mutex.withLock {
        val inserted = store.insert(message)
        if (inserted) refreshGroupLocked(message.ownerIdentityId, message.groupId)
        inserted
    }

    /**
     * Flip a single message's [MessageStatus]. Sweeps every cached
     * group's flow for the matching id and updates in place — chat
     * status callbacks land on the outgoing pipeline without
     * needing to know the source group.
     */
    suspend fun updateStatus(id: UUID, status: MessageStatus) = mutex.withLock {
        store.updateStatus(id, status)
        for ((_, flow) in perGroup) {
            val current = flow.value
            val replaced = current.map { if (it.id == id) it.copy(status = status) else it }
            // Only emit when a row actually changed — avoids spurious
            // re-renders on groups that didn't contain the id.
            if (replaced !== current && replaced != current) {
                flow.value = replaced
            }
        }
    }

    /**
     * Cascade entry point for identity removal. The store is
     * already wiped by [registerRemovalListener]'s closure; this
     * method just refreshes every cached group so subscribers see
     * the updated state immediately.
     */
    suspend fun removeForOwner(ownerId: String) = mutex.withLock {
        store.deleteForOwner(ownerId)
        // Refresh every cached group under the currently-active
        // identity. Other-identity flows (if any are cached) are
        // unaffected — their rows in the store weren't touched.
        val activeOwnerId = identity.currentIdentityId.value?.value
        for ((groupId, _) in perGroup) {
            refreshGroupLocked(activeOwnerId, groupId)
        }
    }

    /**
     * Cascade entry point for group deletion. Drops the store rows
     * and removes the cached flow.
     */
    suspend fun removeForGroup(groupId: String) = mutex.withLock {
        store.deleteForGroup(groupId)
        perGroup.remove(groupId)?.also { it.value = emptyList() }
    }

    // ─── private ──────────────────────────────────────────────────

    private suspend fun handleOwnerRemovalLocked(id: IdentityId) {
        store.deleteForOwner(id.value)
        // Don't wipe perGroup wholesale — other-identity rows in
        // the same group must survive. Refresh under the
        // currently-active identity instead.
        val activeOwnerId = identity.currentIdentityId.value?.value
        for ((groupId, _) in perGroup) {
            refreshGroupLocked(activeOwnerId, groupId)
        }
    }

    private suspend fun refreshGroupLocked(ownerId: String?, groupId: String) {
        val flow = perGroup[groupId] ?: return
        flow.value = if (ownerId == null) {
            emptyList()
        } else {
            store.listForGroup(ownerId, groupId)
        }
    }
}
