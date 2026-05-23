package app.onym.android.chain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the cache + retry decorator that tames the launch-time
 * `get_commitment` storm and survives transient relayer throttling.
 *
 * Mirrors `CachingChainStateReaderTests` from onym-ios PR #169.
 */
class CachingChainStateReaderTest {

    private val groupId = ByteArray(32) { 0x42 }

    @Test
    fun cacheHit_collapsesRepeatReadsWithinTtl() = runTest {
        val inner = CountingChainState().apply { setResult(Result.success(entry(epoch = 3uL))) }
        val reader = CachingChainStateReader(inner = inner, ttlMillis = 60_000, baseRetryDelayMillis = 0)

        reader.tyrannyCommitment(groupId)
        reader.tyrannyCommitment(groupId)

        assertEquals("second read within TTL is served from cache", 1, inner.callCount)
    }

    @Test
    fun cacheExpiry_readsAgainAfterTtl() = runTest {
        val inner = CountingChainState().apply { setResult(Result.success(entry(epoch = 3uL))) }
        // Clock the reader off a controllable now() so we can step past TTL.
        var clock = 1_000_000L
        val reader = CachingChainStateReader(
            inner = inner,
            ttlMillis = 10_000,
            baseRetryDelayMillis = 0,
            now = { clock },
        )

        reader.tyrannyCommitment(groupId)
        clock += 11_000  // past the 10s TTL
        reader.tyrannyCommitment(groupId)

        assertEquals("an expired cache entry triggers a fresh read", 2, inner.callCount)
    }

    @Test
    fun retry_recoversFromTransientFailure() = runTest {
        val inner = CountingChainState().apply {
            // Throw twice, then succeed — within the 3-attempt budget.
            setSequence(
                listOf(
                    Result.failure(ChainReadError.NoActiveRelayer),
                    Result.failure(ChainReadError.NoActiveRelayer),
                    Result.success(entry(epoch = 7uL)),
                ),
            )
        }
        val reader = CachingChainStateReader(
            inner = inner,
            ttlMillis = 10_000,
            maxAttempts = 3,
            baseRetryDelayMillis = 0,
        )

        val result = reader.tyrannyCommitment(groupId)

        assertEquals("retry rides out two transient failures", 7uL, result.epoch)
        assertEquals(3, inner.callCount)
    }

    @Test
    fun retry_exhausted_rethrows() = runTest {
        val inner = CountingChainState().apply {
            setResult(Result.failure(ChainReadError.NoContractBinding))
        }
        val reader = CachingChainStateReader(
            inner = inner,
            ttlMillis = 10_000,
            maxAttempts = 3,
            baseRetryDelayMillis = 0,
        )

        try {
            reader.tyrannyCommitment(groupId)
            fail("should have thrown after exhausting retries")
        } catch (e: Throwable) {
            assertTrue(e is ChainReadError.NoContractBinding)
        }
        assertEquals("all attempts are spent before giving up", 3, inner.callCount)
    }

    private fun entry(epoch: ULong) = SepCommitmentEntry(
        commitment = ByteArray(32) { 0xCC.toByte() },
        epoch = epoch,
    )
}

/**
 * Inner reader that counts calls and yields a configurable result (fixed
 * or a per-call sequence).
 */
private class CountingChainState : ChainStateReading {
    var callCount = 0
        private set
    private var fixed: Result<SepCommitmentEntry> = Result.failure(ChainReadError.NoActiveRelayer)
    private var sequence: ArrayDeque<Result<SepCommitmentEntry>> = ArrayDeque()

    fun setResult(result: Result<SepCommitmentEntry>) { fixed = result }
    fun setSequence(results: List<Result<SepCommitmentEntry>>) { sequence = ArrayDeque(results) }

    override suspend fun tyrannyCommitment(groupId: ByteArray): SepCommitmentEntry {
        callCount += 1
        val next = if (sequence.isNotEmpty()) sequence.removeFirst() else fixed
        return next.getOrThrow()
    }
}
