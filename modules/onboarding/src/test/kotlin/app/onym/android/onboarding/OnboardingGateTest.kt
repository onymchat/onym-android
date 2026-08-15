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

    // ── Explicit restart (Settings → Restart Onboarding) ──────────

    @Test
    fun restartRequested_overridesGrandfathering() = runTest {
        // The whole point of the bit: a user asking to re-run setup
        // is by definition a configured (grandfathered) user, so the
        // request must win over the probe.
        val store = InMemoryOnboardingStore(initiallyCompleted = false)
        store.requestRestart()
        assertTrue(OnboardingGate.shouldOnboard(store) { true })
    }

    @Test
    fun restartRequested_overridesTheCompletedFlag() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        store.requestRestart()
        assertFalse("requestRestart clears the flag", store.completed)
        assertTrue(OnboardingGate.shouldOnboard(store) { true })
    }

    @Test
    fun restartRequested_skipsTheProbeEntirely() = runTest {
        // Explicit intent, not a probe question — the cold-boot
        // grandfathering read must not run (and so can't veto).
        val store = InMemoryOnboardingStore()
        store.requestRestart()
        var probes = 0
        assertTrue(OnboardingGate.shouldOnboard(store) { probes += 1; true })
        assertEquals(0, probes)
    }

    @Test
    fun completingTheRestartWalk_clearsTheRequest_gateGoesQuiet() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        store.requestRestart()
        assertTrue(OnboardingGate.shouldOnboard(store) { true })

        // The walk finishes the same way a first run does.
        store.markCompleted()
        assertFalse(store.restartRequested)
        assertFalse(OnboardingGate.shouldOnboard(store) { true })
    }

    @Test
    fun restartRequest_persistsAcrossResolutions_untilCompleted() = runTest {
        // Mid-walk kill: the next launch re-resolves and must still
        // onboard — the request is durable, not consumed by the read.
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        store.requestRestart()
        repeat(3) {
            assertTrue(OnboardingGate.shouldOnboard(store) { true })
        }
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
