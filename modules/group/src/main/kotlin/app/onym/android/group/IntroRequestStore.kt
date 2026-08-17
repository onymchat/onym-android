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

    /** Drop a handled request and tombstone its id. Implementations
     *  MUST tombstone durably: cold starts replay the inbox too. */
    suspend fun consume(id: String)

    /** Drop tombstones for links that no longer exist, so the durable
     *  set stays bounded by the live invites. */
    suspend fun pruneTombstones(retiringPublicKeysHex: Set<String>)

    /** One owner-agnostic sweep at startup; see
     *  [HandledIntroRequestLog.reconcile]. */
    suspend fun reconcileTombstones(livePublicKeysHex: Set<String>)

    /** Remember a declined joiner so the decline survives their retry
     *  and a relaunch. Keyed by collapse key, not event id. */
    suspend fun recordDeclined(collapseKey: String)

    /** Collapse keys the host declined; the approver filters on these. */
    suspend fun declinedCollapseKeys(): Set<String>
}

class InMemoryIntroRequestStore(
    /** Durable half of the tombstone. Process-lifetime alone would let
     *  every handled request re-decode on the next cold start. */
    private val handledLog: HandledIntroRequestLog = InMemoryHandledIntroRequestLog(),
) : IntroRequestStore {

    private val mutex = Mutex()
    private val pending = mutableListOf<IntroRequest>()

    /** Mirrors [handledLog] so the hot [record] path doesn't hit
     *  storage per inbound. Seeded lazily on first use. */
    private val consumed = mutableSetOf<String>()
    private var seeded = false

    private val _requests = MutableStateFlow<List<IntroRequest>>(emptyList())
    override val requests: StateFlow<List<IntroRequest>> = _requests.asStateFlow()

    override suspend fun record(request: IntroRequest): Boolean = mutex.withLock {
        seedConsumedUnlocked()
        if (request.id in consumed) return@withLock false
        if (pending.any { it.id == request.id }) return@withLock false
        pending += request
        _requests.value = pending.sortedByDescending { it.receivedAt }
        true
    }

    override suspend fun consume(id: String) = mutex.withLock {
        seedConsumedUnlocked()
        // Tombstone unconditionally, even when the id isn't pending, so
        // a tombstone can be laid ahead of a replay that hasn't landed.
        consumed += id
        // Attribute to the link it arrived on, so [pruneTombstones] can
        // drop it when that link is retired.
        // Durable even when the request isn't pending (a tombstone laid
        // ahead of a replay, or a stale sibling id). With no link to
        // attribute it to it goes in unattributed: never pruned by a
        // retired link, bounded only by the log's own cap — the right
        // trade, since losing it means the handled request comes back
        // as pending on the next cold start.
        handledLog.record(
            id,
            pending.firstOrNull { it.id == id }?.targetIntroPublicKey?.toHexLower()
                ?: UNATTRIBUTED_LINK,
        )
        if (pending.removeAll { it.id == id }) {
            _requests.value = pending.sortedByDescending { it.receivedAt }
        }
    }

    private companion object {
        /** Stands in for "no link known". Not 64 hex chars, so it can
         *  never collide with a real intro pubkey in a retired set. */
        const val UNATTRIBUTED_LINK = ""
    }

    override suspend fun reconcileTombstones(livePublicKeysHex: Set<String>) = mutex.withLock {
        handledLog.reconcile(livePublicKeysHex)
        consumed.clear()
        consumed += handledLog.handledIds()
        seeded = true
    }

    override suspend fun recordDeclined(collapseKey: String) {
        handledLog.recordDeclined(collapseKey)
    }

    override suspend fun declinedCollapseKeys(): Set<String> = handledLog.declinedCollapseKeys()

    override suspend fun pruneTombstones(retiringPublicKeysHex: Set<String>) = mutex.withLock {
        handledLog.prune(retiringPublicKeysHex)
        consumed.clear()
        consumed += handledLog.handledIds()
        seeded = true
    }

    private suspend fun seedConsumedUnlocked() {
        if (seeded) return
        consumed += handledLog.handledIds()
        seeded = true
    }
}

private fun ByteArray.toHexLower(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }
