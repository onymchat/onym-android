package app.onym.android.group

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

/**
 * Owns the in-memory view of the **currently-selected identity's**
 * chat groups plus the corresponding writes to the persistence seam.
 * Touch-surface compliance:
 *
 *  - Holds a [GroupStore] reference and a [app.onym.android.identity.IdentityRepository]
 *    (for the active-identity flow + the cascade-delete listener).
 *  - Exposes a [StateFlow] for observation; mutators are suspend.
 *  - Mutations serialise through a [Mutex]; reads are lock-free
 *    off the [StateFlow].
 *
 * **Per-identity filter**: [snapshots] only emits groups whose
 * [ChatGroup.ownerIdentityId] matches the currently-selected
 * [IdentityId]. Switching identity → recompute. No identity
 * selected → empty list.
 *
 * **Cascade delete**: registers a removal listener on
 * [IdentityRepository] so when the user removes an identity, every
 * group owned by that identity is deleted from disk before the
 * identity's secrets are wiped.
 *
 * Mirrors `GroupRepository.swift` from onym-ios PR #25 (extended
 * for multi-identity in PR-3).
 */
class GroupRepository(
    private val store: GroupStore,
    private val identity: ActiveIdentityProvider,
    /** Scope owning the per-identity-selection collector. The
     *  collector lives for the process lifetime; pass an
     *  `applicationScope` (SupervisorJob + Dispatchers.IO). */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow<List<ChatGroup>>(emptyList())

    /** Hot stream of the **currently-selected identity's** groups,
     *  sorted by [ChatGroup.createdAtMillis] desc. Empty until
     *  [start] runs OR until an identity is selected — whichever
     *  comes first. */
    val snapshots: StateFlow<List<ChatGroup>> = _snapshots.asStateFlow()

    init {
        // Cascade delete on identity removal. Single-listener — if
        // anyone else needs the same hook, fan out via a multiplexer.
        identity.registerRemovalListener { id ->
            mutex.withLock { store.deleteForOwner(id.value) }
        }
    }

    /**
     * Start observing the active-identity flow. Idempotent — safe to
     * call from `onCreate`. Recomputes [snapshots] every time the
     * active identity changes (including → null, which empties the
     * list).
     */
    fun start() {
        scope.launch {
            identity.currentIdentityId.collectLatest { id ->
                mutex.withLock { refreshLocked(id) }
            }
        }
    }

    /**
     * Hydrate from disk for the currently-selected identity.
     * Idempotent — safe to call any time. Mostly redundant with
     * [start]'s automatic recompute on identity change, but kept for
     * tests + edge-case callers that want a deterministic refresh.
     */
    suspend fun reload() = mutex.withLock {
        refreshLocked(identity.currentIdentityId.value)
    }

    /**
     * Idempotent on [ChatGroup.id] (delegates to
     * [GroupStore.insertOrUpdate]). Any subsequent insert with the
     * same id overwrites the row in place — the chain-anchor flow
     * uses this to flip [ChatGroup.isPublishedOnChain] and bump the
     * commitment.
     *
     * @return `true` on insert, `false` on update.
     */
    suspend fun insert(group: ChatGroup): Boolean = mutex.withLock {
        val inserted = store.insertOrUpdate(group)
        refreshLocked(identity.currentIdentityId.value)
        inserted
    }

    /**
     * Mark a group as anchored on chain. The commitment, when
     * supplied, replaces whatever was held in memory; a `null`
     * commitment leaves the existing column untouched.
     */
    suspend fun markPublished(id: String, commitment: ByteArray?) = mutex.withLock {
        store.markPublished(id, commitment)
        refreshLocked(identity.currentIdentityId.value)
    }

    suspend fun delete(id: String) = mutex.withLock {
        store.delete(id)
        refreshLocked(identity.currentIdentityId.value)
    }

    /**
     * Cross-identity lookup. Returns the [ChatGroup] owned by
     * [ownerIdentityId] with hex [groupId] (matched against
     * [ChatGroup.id]), or `null` if no such row exists. Reads
     * directly from the store — bypasses the active-identity filter
     * on [snapshots] so an inbox dispatcher can route an incoming
     * payload to the correct identity's group even when that
     * identity isn't the currently-selected one.
     */
    suspend fun findForOwner(ownerIdentityId: String, groupId: String): ChatGroup? =
        mutex.withLock {
            store.listForOwner(ownerIdentityId).firstOrNull { it.id == groupId }
        }

    private suspend fun refreshLocked(activeId: IdentityId?) {
        _snapshots.value = if (activeId == null) {
            emptyList()
        } else {
            store.listForOwner(activeId.value)
        }
    }
}
