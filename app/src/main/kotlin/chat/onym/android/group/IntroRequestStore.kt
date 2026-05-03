package chat.onym.android.group

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
 * Mirrors the [chat.onym.android.persistence.InMemoryInvitationStore]
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
     *  Decline) so it stops cluttering the surface. */
    suspend fun consume(id: String)
}

class InMemoryIntroRequestStore : IntroRequestStore {

    private val mutex = Mutex()
    private val pending = mutableListOf<IntroRequest>()

    private val _requests = MutableStateFlow<List<IntroRequest>>(emptyList())
    override val requests: StateFlow<List<IntroRequest>> = _requests.asStateFlow()

    override suspend fun record(request: IntroRequest): Boolean = mutex.withLock {
        if (pending.any { it.id == request.id }) return@withLock false
        pending += request
        _requests.value = pending.sortedByDescending { it.receivedAt }
        true
    }

    override suspend fun consume(id: String) = mutex.withLock {
        if (pending.removeAll { it.id == id }) {
            _requests.value = pending.sortedByDescending { it.receivedAt }
        }
    }
}
