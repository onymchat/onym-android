package app.onym.android.uitests.screens

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.onym.android.onboarding.OnboardingStep
import org.junit.Assert.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Page object for the first-launch onboarding walk
 * ([app.onym.android.onboarding.OnboardingScreen] hosted by
 * [app.onym.android.OnboardingHost]). Wraps the
 * `onboarding.<step>.<element>` test-tag vocabulary from the
 * :onboarding scaffold plus the app-layer step-content tags
 * (`onboarding.discoveryConfirm.*`, `onboarding.notary.*`), in the
 * same style as [DiscoverySettingsScreenObject].
 */
class OnboardingScreenObject(private val rule: ComposeContentTestRule) {

    // ─── frame (scaffold) ─────────────────────────────────────────

    fun title(step: OnboardingStep): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.${step.tag}.title")

    fun primaryButton(step: OnboardingStep): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.${step.tag}.primary")

    fun skipButton(step: OnboardingStep): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.${step.tag}.skip")

    fun backButton(step: OnboardingStep): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.${step.tag}.back")

    /** Wait until [step]'s frame is on screen (the gate resolves
     *  asynchronously, and step changes recompose). */
    fun awaitStep(step: OnboardingStep): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.${step.tag}.title")
                .fetchSemanticsNodes().isNotEmpty()
        }
        title(step).assertIsDisplayed()
        return this
    }

    fun tapPrimary(step: OnboardingStep): OnboardingScreenObject {
        primaryButton(step).performClick()
        return this
    }

    fun tapSkip(step: OnboardingStep): OnboardingScreenObject {
        skipButton(step).performClick()
        return this
    }

    fun tapBack(step: OnboardingStep): OnboardingScreenObject {
        backButton(step).performClick()
        return this
    }

    fun assertNotShown(): OnboardingScreenObject {
        for (step in OnboardingStep.entries) {
            assertTrue(
                "onboarding step '${step.tag}' unexpectedly on screen",
                rule.onAllNodesWithTag("onboarding.${step.tag}.title")
                    .fetchSemanticsNodes().isEmpty(),
            )
        }
        return this
    }

    // ─── discoveryConfirm step content ────────────────────────────

    fun reviewFingerprintButton(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.discoveryConfirm.review")

    fun fingerprint(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.discoveryConfirm.fingerprint")

    fun confirmPinButton(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.discoveryConfirm.confirm")

    fun pinnedState(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.discoveryConfirm.pinned")

    fun loadingState(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.discoveryConfirm.loading")

    fun awaitReviewHero(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.discoveryConfirm.review")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    fun tapReviewFingerprint(): OnboardingScreenObject {
        reviewFingerprintButton().performScrollTo().performClick()
        return this
    }

    fun awaitFingerprint(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.discoveryConfirm.fingerprint")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    fun assertFingerprint(expected: String): OnboardingScreenObject {
        fingerprint().assertTextEquals(expected)
        return this
    }

    fun tapConfirmPin(): OnboardingScreenObject {
        confirmPinButton().performScrollTo().performClick()
        return this
    }

    fun awaitPinned(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.discoveryConfirm.pinned")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    fun awaitDiscoveryLoading(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.discoveryConfirm.loading")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    // ─── notary step content ──────────────────────────────────────

    fun notaryAddButton(url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.notary.add.$url")

    fun awaitNotaryPublishedRow(url: String): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.notary.known.$url")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    fun tapNotaryAdd(url: String): OnboardingScreenObject {
        notaryAddButton(url).performScrollTo().performClick()
        return this
    }

    /** The Add affordance flipped to the ADDED chip: the add BUTTON is
     *  gone while the row itself is still listed. */
    fun awaitNotaryAdded(url: String): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("onboarding.notary.add.$url")
                .fetchSemanticsNodes().isEmpty()
        }
        return awaitNotaryPublishedRow(url)
    }

    // ─── after the walk ───────────────────────────────────────────

    /** The tab shell replaced the walk. */
    fun awaitTabShell(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("nav.tab.chats").fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }
}
