package app.onym.android.support

import app.onym.android.onboarding.OnboardingStore

/**
 * In-memory [OnboardingStore] for tests: this module's flow/gate
 * tests and :app's gate-wiring/UI tests (PR 3/4) share this one fake
 * instead of each rolling their own.
 */
class InMemoryOnboardingStore(
    initiallyCompleted: Boolean = false,
) : OnboardingStore {

    var completed: Boolean = initiallyCompleted
        private set

    /** Number of [markCompleted] calls — lets tests assert the flag
     *  is written exactly once (and never by the gate). */
    var markCount: Int = 0
        private set

    override suspend fun hasCompleted(): Boolean = completed

    override suspend fun markCompleted() {
        completed = true
        markCount += 1
    }

    override suspend fun reset() {
        completed = false
    }
}
