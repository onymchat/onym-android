package app.onym.android.support

import app.onym.android.onboarding.OnboardingStore

/**
 * In-memory [OnboardingStore] for tests: this module's flow/gate
 * tests and :app's gate-wiring/UI tests (PR 3/4) share this one fake
 * instead of each rolling their own.
 */
class InMemoryOnboardingStore(
    initiallyCompleted: Boolean = false,
    /** Terms agreed already, so walk tests needn't tick the box. */
    termsAccepted: Boolean = true,
) : OnboardingStore {

    var termsAccepted: Boolean = termsAccepted
        private set

    override suspend fun hasAcceptedTerms(): Boolean = termsAccepted

    override suspend fun setTermsAccepted(accepted: Boolean) {
        termsAccepted = accepted
    }

    var completed: Boolean = initiallyCompleted
        private set

    /** Number of [markCompleted] calls — lets tests assert the flag
     *  is written exactly once (and never by the gate). */
    var markCount: Int = 0
        private set

    var restartRequested: Boolean = false
        private set

    override suspend fun hasCompleted(): Boolean = completed

    override suspend fun markCompleted() {
        completed = true
        restartRequested = false
        markCount += 1
    }

    override suspend fun reset() {
        completed = false
        termsAccepted = false
    }

    override suspend fun isRestartRequested(): Boolean = restartRequested

    override suspend fun requestRestart() {
        completed = false
        restartRequested = true
    }
}
