package app.onym.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [probeIdentityReady] — the onboarding identity step's bootstrap
 * check. The load-bearing distinction: a real bootstrap failure
 * answers false (the step renders its failure card with Try again),
 * but a CANCELLED check must propagate as cancellation — swallowing
 * it (the old `runCatching { … }.isSuccess`) flipped a mere
 * navigation-away into a scary failure card.
 */
class IdentityReadyProbeTest {

    @Test
    fun success_answersTrue() = runTest {
        assertTrue(probeIdentityReady { /* bootstrap ok */ })
    }

    @Test
    fun failure_answersFalse() = runTest {
        assertFalse(probeIdentityReady { throw IllegalStateException("keystore sad") })
    }

    @Test
    fun cancellation_isRethrown_notSwallowedAsFailure() = runTest {
        try {
            probeIdentityReady { throw CancellationException("scope left") }
            fail("cancellation must propagate, not answer false")
        } catch (expected: CancellationException) {
            // The cooperative-cancellation contract: the caller's
            // scope handles this; the identity step never sees a
            // fake failure.
        }
    }
}
