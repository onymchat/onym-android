package chat.onym.android.group

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the in-memory view of the user's chat groups plus the
 * corresponding writes to the persistence seam. Touch-surface
 * compliance:
 *
 *  - Holds **only** a [GroupStore] reference. No transport, no
 *    OnymSDK, no Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [insert] / [markPublished]
 *    / [delete] / [reload] are the suspend mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free
 *    off the [StateFlow].
 *
 * iOS uses `actor + AsyncStream` for the same role; Android stays
 * with [StateFlow] because Compose subscribes via
 * `collectAsStateWithLifecycle()` with no per-view boilerplate (same
 * argument as `IncomingInvitationsRepository` from PR #16).
 *
 * **Replay-on-subscribe** is intentional — every new collector
 * receives the current value immediately. Don't swap [StateFlow]
 * for [kotlinx.coroutines.flow.MutableSharedFlow]; PR-C's screen
 * relies on the replay so it can render an immediate snapshot
 * without a `LaunchedEffect { reload() }` dance.
 *
 * Mirrors `GroupRepository.swift` from onym-ios PR #25.
 */
class GroupRepository(
    private val store: GroupStore,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow<List<ChatGroup>>(emptyList())

    /** Hot stream of the user's groups, sorted by [ChatGroup.createdAtMillis]
     *  desc. Empty until [reload] (or any mutator) runs. */
    val snapshots: StateFlow<List<ChatGroup>> = _snapshots.asStateFlow()

    /**
     * Hydrate from disk. Idempotent — safe to call from any
     * `onCreate` / `LaunchedEffect`. Repeated calls reload but don't
     * error.
     */
    suspend fun reload() = mutex.withLock { refreshLocked() }

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
        refreshLocked()
        inserted
    }

    /**
     * Mark a group as anchored on chain. The commitment, when
     * supplied, replaces whatever was held in memory (the relayer's
     * `get_state` is the source of truth post-anchor); a `null`
     * commitment leaves the existing column untouched.
     */
    suspend fun markPublished(id: String, commitment: ByteArray?) = mutex.withLock {
        store.markPublished(id, commitment)
        refreshLocked()
    }

    suspend fun delete(id: String) = mutex.withLock {
        store.delete(id)
        refreshLocked()
    }

    private suspend fun refreshLocked() {
        _snapshots.value = store.list()
    }
}
