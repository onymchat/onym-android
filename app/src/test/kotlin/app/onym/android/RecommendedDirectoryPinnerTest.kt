package app.onym.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * [RecommendedDirectoryPinner] — the accept-triggers-pin seam for
 * the seeded default directory. The contract under test:
 *
 *  - unpinned seeded source → the programmatic TOFU runs exactly
 *    once and reports [RecommendedDirectoryPinner.Outcome.Pinned];
 *  - already pinned (earlier accept / hub confirm) → idempotent
 *    no-op;
 *  - source removed by the user → no-op, NEVER re-added;
 *  - fetch/verify failure (offline first-run) → swallowed as
 *    [RecommendedDirectoryPinner.Outcome.Failed], never thrown — an
 *    offline accept must not block the walk;
 *  - cancellation still propagates (cooperative-cancellation
 *    contract, same as probeIdentityReady).
 */
class RecommendedDirectoryPinnerTest {

    @Test
    fun unpinnedSeed_pinsExactlyOnce_thenIdempotent() = runTest {
        var pinned = false
        var pinCalls = 0
        val pinner = RecommendedDirectoryPinner(
            seededSourcePinned = { pinned },
            pinSeededSource = { pinCalls += 1; pinned = true },
        )

        assertEquals(RecommendedDirectoryPinner.Outcome.Pinned, pinner.pinIfUnpinned())
        assertEquals(1, pinCalls)

        // The second accept (re-advance after Back) is a no-op: the
        // status probe now answers pinned.
        assertEquals(
            RecommendedDirectoryPinner.Outcome.AlreadyPinned,
            pinner.pinIfUnpinned(),
        )
        assertEquals(1, pinCalls)
    }

    @Test
    fun alreadyPinned_neverCallsThePinPath() = runTest {
        var pinCalls = 0
        val pinner = RecommendedDirectoryPinner(
            seededSourcePinned = { true },
            pinSeededSource = { pinCalls += 1 },
        )
        assertEquals(
            RecommendedDirectoryPinner.Outcome.AlreadyPinned,
            pinner.pinIfUnpinned(),
        )
        assertEquals(0, pinCalls)
    }

    @Test
    fun removedSeed_isRespected_neverReAdded() = runTest {
        var pinCalls = 0
        val pinner = RecommendedDirectoryPinner(
            seededSourcePinned = { null },
            pinSeededSource = { pinCalls += 1 },
        )
        assertEquals(
            RecommendedDirectoryPinner.Outcome.SourceAbsent,
            pinner.pinIfUnpinned(),
        )
        assertEquals(0, pinCalls)
    }

    @Test
    fun pinFailure_isSwallowedAsFailed_walkNotBlocked() = runTest {
        val pinner = RecommendedDirectoryPinner(
            seededSourcePinned = { false },
            pinSeededSource = { throw IllegalStateException("offline first-run") },
        )
        // Must not throw — the accept path runs fire-and-forget off
        // the walk, and an offline first run keeps advancing.
        assertEquals(RecommendedDirectoryPinner.Outcome.Failed, pinner.pinIfUnpinned())

        // And the source stayed unpinned, so a later accept retries.
        var pinnedNow = false
        val retrying = RecommendedDirectoryPinner(
            seededSourcePinned = { pinnedNow },
            pinSeededSource = { pinnedNow = true },
        )
        assertEquals(RecommendedDirectoryPinner.Outcome.Pinned, retrying.pinIfUnpinned())
    }

    @Test
    fun slowHydration_isAwaited_thenAnswersFromRealData() = runTest {
        // The status probe hydrates the repository (bootstrap) before
        // reading — an unhydrated snapshot must never masquerade as
        // "the user removed the seed" (SourceAbsent, never retried).
        // The pinner must AWAIT the suspend probe and act on the real
        // post-hydration answer.
        var hydrated = false
        var pinCalls = 0
        val pinner = RecommendedDirectoryPinner(
            seededSourcePinned = {
                kotlinx.coroutines.delay(5_000) // slow disk load
                hydrated = true
                false // hydrated answer: present, unpinned
            },
            pinSeededSource = {
                check(hydrated) { "pin ran before hydration resolved" }
                pinCalls += 1
            },
        )
        assertEquals(RecommendedDirectoryPinner.Outcome.Pinned, pinner.pinIfUnpinned())
        assertEquals(1, pinCalls)
    }

    @Test
    fun cancellation_propagates() = runTest {
        val pinner = RecommendedDirectoryPinner(
            seededSourcePinned = { false },
            pinSeededSource = { throw CancellationException("scope left") },
        )
        try {
            pinner.pinIfUnpinned()
            fail("cancellation must propagate, not read as Failed")
        } catch (expected: CancellationException) {
            // cooperative cancellation flows through
        }
    }
}
