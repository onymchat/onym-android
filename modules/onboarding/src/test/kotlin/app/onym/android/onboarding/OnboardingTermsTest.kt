package app.onym.android.onboarding

import app.onym.android.support.InMemoryOnboardingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** App Review guideline 1.2: the Terms of Use are agreed before anything
 *  else — onym-ios `OnboardingFlowTests` terms cases. */
class OnboardingTermsTest {

    @Test
    fun welcome_waitsForTheTerms() = runTest {
        val store = InMemoryOnboardingStore(termsAccepted = false)
        val flow = OnboardingFlow(store = store, requireTerms = true)
        flow.start()
        assertFalse(flow.state.value.termsAccepted)

        flow.skipAll()
        assertFalse("the door waits on the terms", store.completed)
        flow.advance()
        assertEquals("so does the step-by-step walk", OnboardingStep.Welcome, flow.state.value.step)

        flow.setTermsAccepted(true)
        assertTrue("persisted when checked", store.hasAcceptedTerms())
        flow.skipAll()
        assertTrue(store.completed)
    }

    @Test
    fun uncheckingTheTerms_withdrawsTheAgreement() = runTest {
        val store = InMemoryOnboardingStore(termsAccepted = false)
        val flow = OnboardingFlow(store = store, requireTerms = true)
        flow.start()
        flow.setTermsAccepted(true)
        flow.setTermsAccepted(false)
        assertFalse(store.hasAcceptedTerms())
        flow.advance()
        assertEquals(OnboardingStep.Welcome, flow.state.value.step)
    }

    @Test
    fun anAgreementFromEarlier_opensTheWelcomeScreen() = runTest {
        val flow = OnboardingFlow(store = InMemoryOnboardingStore(termsAccepted = true), requireTerms = true)
        flow.start()
        assertTrue(flow.termsSatisfied())
    }

    /** Someone onboarded before there were terms sees the welcome screen
     *  once more — grandfathering doesn't excuse the agreement. */
    @Test
    fun gate_asksAnyoneWhoNeverAgreed() = runTest {
        val store = InMemoryOnboardingStore(initiallyCompleted = true, termsAccepted = false)
        assertTrue(OnboardingGate.shouldOnboard(store = store, isExistingUser = { true }))
        store.setTermsAccepted(true)
        assertFalse(OnboardingGate.shouldOnboard(store = store, isExistingUser = { true }))
    }
}
