package app.onym.android.onboarding

import app.onym.android.support.InMemoryOnboardingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnboardingGate.shouldOnboard]: the grandfathering truth table, the
 * lazy existing-user probe, and the pure-read guarantee (a
 * grandfathered user never gets the flag written for them).
 */
class OnboardingGateTest {

    // ── Truth table: flag × existing-user ─────────────────────────

    @Test
    fun freshInstall_noFlagNoState_onboards() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = false)
        assertTrue(OnboardingGate.shouldOnboard(store) { false })
    }

    @Test
    fun completedFlag_skipsOnboarding() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        assertFalse(OnboardingGate.shouldOnboard(store) { false })
    }

    @Test
    fun grandfatheredUser_noFlagButExistingState_skipsOnboarding() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = false)
        assertFalse(OnboardingGate.shouldOnboard(store) { true })
    }

    @Test
    fun completedFlagAndExistingState_skipsOnboarding() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        assertFalse(OnboardingGate.shouldOnboard(store) { true })
    }

    // ── Probe laziness + purity ───────────────────────────────────

    @Test
    fun existingUserProbe_isNotConsulted_whenTheFlagIsSet() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        var probes = 0
        OnboardingGate.shouldOnboard(store) { probes += 1; true }
        // Steady-state path: the (potentially costly) probe never runs.
        assertEquals(0, probes)
    }

    @Test
    fun existingUserProbe_isConsulted_whenTheFlagIsAbsent() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = false)
        var probes = 0
        OnboardingGate.shouldOnboard(store) { probes += 1; true }
        assertEquals(1, probes)
    }

    @Test
    fun gate_neverWritesTheFlag_grandfatheredUsersStayUnflagged() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = false)
        // A grandfathered user answers false on every launch — the
        // gate is a pure read, so the flag stays absent forever and
        // the existing-user signal keeps carrying the decision.
        repeat(3) {
            assertFalse(OnboardingGate.shouldOnboard(store) { true })
        }
        assertFalse(store.completed)
        assertEquals(0, store.markCount)
    }
}
