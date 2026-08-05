package app.onym.android.group

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory sink for inbound intro requests. Process-lifetime —
 * the request approval flow (PR-4) is interactive; if the user
 * doesn't act before the process dies, the joiner re-shares.
 *
 * Mirrors the [app.onym.android.persistence.InMemoryInvitationStore]
 * shape — identical posture for the V1 receive-side (interactive
 * UI consumes the StateFlow; persistence lands in a follow-up if
 * we need durability across restarts).
 */
interface IntroRequestStore {
    /** Hot stream of pending requests. Sorted newest-first by
     *  [IntroRequest.receivedAt]. UI subscribes here. */
    val requests: StateFlow<List<IntroRequest>>

    /** Append a fresh request. Dedup on [IntroRequest.id]; returns
     *  `true` on insert, `false` if the id was already present. */
    suspend fun record(request: IntroRequest): Boolean

    /** Drop a request after the user has acted on it (Approve or
     *  Decline) so it stops cluttering the surface, and remember its
     *  id so a replay can't resurrect it.
     *
     *  Implementations MUST tombstone. `NostrInboxTransport`'s REQ
     *  carries no `since` and no `limit`, so every relay reconnect
     *  re-delivers the intro inbox in full. Dropping the row from
     *  `pending` alone means the next replay re-records it and the
     *  user sees a request they already handled. */
    suspend fun consume(id: String)
}

class InMemoryIntroRequestStore : IntroRequestStore {

    private val mutex = Mutex()
    private val pending = mutableListOf<IntroRequest>()

    /** Event ids the user already acted on. Process-lifetime, matching
     *  [pending]: a relaunch legitimately re-surfaces anything the user
     *  never got round to, but within a session an approved or declined
     *  row must stay gone. */
    private val consumed = mutableSetOf<String>()

    private val _requests = MutableStateFlow<List<IntroRequest>>(emptyList())
    override val requests: StateFlow<List<IntroRequest>> = _requests.asStateFlow()

    override suspend fun record(request: IntroRequest): Boolean = mutex.withLock {
        if (request.id in consumed) return@withLock false
        if (pending.any { it.id == request.id }) return@withLock false
        pending += request
        _requests.value = pending.sortedByDescending { it.receivedAt }
        true
    }

    override suspend fun consume(id: String) = mutex.withLock {
        // Tombstone unconditionally, even when the id isn't pending, so
        // a tombstone can be laid ahead of a replay that hasn't landed.
        consumed += id
        if (pending.removeAll { it.id == id }) {
            _requests.value = pending.sortedByDescending { it.receivedAt }
        }
    }
}
