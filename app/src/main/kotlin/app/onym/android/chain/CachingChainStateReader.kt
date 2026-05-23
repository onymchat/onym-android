package app.onym.android.chain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps a [ChainStateReading] with two defenses against the launch-time
 * `get_commitment` request storm and the relayer throttling it triggers:
 *
 *  1. **Bounded retry** — a single transient read failure (relayer 429 /
 *     network blip) no longer surfaces as "couldn't verify". We retry a
 *     few times with linear backoff before giving up, so a momentarily
 *     throttled relayer doesn't make a fresh join silently fail.
 *  2. **Short TTL cache** — collapses a burst of reads for the *same*
 *     group (e.g. an invitation plus several member announcements
 *     replayed together on a relay reconnect) into one round-trip.
 *
 * The TTL is deliberately short: a stale entry can only ever make a
 * caller see an older `(commitment, epoch)`, which the dispatcher treats
 * as "chain behind → defer + retry" (never a reject), so staleness
 * self-heals once the entry expires. It is therefore safe to cache
 * without risking a false rejection — the worst case is a brief deferral.
 *
 * [SepContractChainStateReader] itself stays cache-free (chain state is
 * the source of truth); this decorator is the single place that trades a
 * little staleness for far fewer relayer calls.
 *
 * Mirrors `CachingChainStateReader` from onym-ios PR #169.
 */
class CachingChainStateReader(
    private val inner: ChainStateReading,
    private val ttlMillis: Long = 10_000,
    maxAttempts: Int = 3,
    private val baseRetryDelayMillis: Long = 300,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ChainStateReading {

    private val maxAttempts: Int = maxOf(1, maxAttempts)
    private val mutex = Mutex()
    /** Keyed by group-id hex — `ByteArray` has identity equality, so it's
     *  unusable as a map key directly. */
    private val cache = HashMap<String, CacheEntry>()

    private data class CacheEntry(val entry: SepCommitmentEntry, val at: Long)

    override suspend fun tyrannyCommitment(groupId: ByteArray): SepCommitmentEntry {
        val key = groupId.toHexLowercase()

        mutex.withLock {
            val hit = cache[key]
            if (hit != null && now() - hit.at < ttlMillis) return hit.entry
        }

        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            try {
                val entry = inner.tyrannyCommitment(groupId)
                mutex.withLock { cache[key] = CacheEntry(entry, now()) }
                return entry
            } catch (e: Throwable) {
                lastError = e
                // Linear backoff between attempts; no sleep after the last.
                if (attempt < maxAttempts - 1 && baseRetryDelayMillis > 0) {
                    delay(baseRetryDelayMillis * (attempt + 1))
                }
            }
        }
        throw lastError ?: ChainReadError.NoActiveRelayer
    }

    private companion object {
        private fun ByteArray.toHexLowercase(): String =
            buildString(size * 2) { for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF)) }
    }
}
