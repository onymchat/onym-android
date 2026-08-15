package app.onym.android

import app.onym.android.onboarding.OnboardingStore
import app.onym.android.support.InMemoryOnboardingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [OnboardingGateProbe.resolvePending] — the fail-open launch-time
 * decision. The review-critical rows: a throwing store or probe must
 * resolve to `false` (no crash, no permanently-null decision, and the
 * caller's `finally` still fires the relayer fetch), and UI-test mode
 * short-circuits without ever touching storage.
 */
class OnboardingGateResolutionTest {

    private class ThrowingStore : OnboardingStore {
        var reads = 0
        override suspend fun hasCompleted(): Boolean {
            reads++
            throw IOException("corrupt prefs")
        }
        override suspend fun markCompleted() = throw IOException("corrupt prefs")
        override suspend fun reset() = throw IOException("corrupt prefs")
        override suspend fun isRestartRequested(): Boolean {
            reads++
            throw IOException("corrupt prefs")
        }
        override suspend fun requestRestart() = throw IOException("corrupt prefs")
    }

    @Test
    fun freshUser_resolvesTrue() = runTest {
        assertTrue(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = InMemoryOnboardingStore(),
                isExistingUser = { false },
            ),
        )
    }

    @Test
    fun completedOrGrandfathered_resolveFalse() = runTest {
        assertFalse(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = InMemoryOnboardingStore(initiallyCompleted = true),
                isExistingUser = { false },
            ),
        )
        assertFalse(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = InMemoryOnboardingStore(),
                isExistingUser = { true },
            ),
        )
    }

    @Test
    fun throwingStore_failsOpenToFalse() = runTest {
        assertFalse(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = ThrowingStore(),
                isExistingUser = { false },
            ),
        )
    }

    @Test
    fun throwingProbe_failsOpenToFalse() = runTest {
        assertFalse(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = InMemoryOnboardingStore(),
                isExistingUser = { throw IOException("storage unavailable") },
            ),
        )
    }

    // ── Explicit restart (Settings → Restart Onboarding) ──────────

    @Test
    fun restartRequested_resolvesTrue_evenForCompletedGrandfatheredUsers() = runTest {
        // The restart action's whole contract: a configured user is
        // by definition grandfathered, so the request must override
        // both the (cleared) flag and the probe.
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        store.requestRestart()
        assertTrue(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = store,
                isExistingUser = { true },
            ),
        )
    }

    @Test
    fun completingTheRestartWalk_resolvesFalseAgain() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        store.requestRestart()
        store.markCompleted()
        assertFalse(store.restartRequested)
        assertFalse(
            OnboardingGateProbe.resolvePending(
                uiTestMode = false,
                store = store,
                isExistingUser = { true },
            ),
        )
    }

    @Test
    fun restartRequest_survivesReResolution_midWalkKillResumes() = runTest {
        // Process death mid-restart-walk: every fresh resolution
        // still presents the walk until complete() clears the bit —
        // no half state where neither the walk nor the tabs mount.
        val store = InMemoryOnboardingStore(initiallyCompleted = true)
        store.requestRestart()
        repeat(3) {
            assertTrue(
                OnboardingGateProbe.resolvePending(
                    uiTestMode = false,
                    store = store,
                    isExistingUser = { true },
                ),
            )
        }
    }

    @Test
    fun uiTestMode_shortCircuits_withoutTouchingStorage() = runTest {
        val store = ThrowingStore()
        assertFalse(
            OnboardingGateProbe.resolvePending(
                uiTestMode = true,
                store = store,
                isExistingUser = { true },
            ),
        )
        // Never read — the harness decision doesn't depend on state.
        assertTrue(store.reads == 0)
    }
}
