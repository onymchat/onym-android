package app.onym.android.uitests.screens

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.onym.android.onboarding.OnboardingStep
import org.junit.Assert.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Page object for the REDESIGNED first-launch onboarding walk:
 * welcome → identity → services (recommended card, or the
 * choose-myself hub with one surface per seat) → (moderation,
 * reserved) → recoveryPhrase → done.
 *
 * Tag vocabulary (the redesign contract; see RECONCILIATION.md at the
 * repo root):
 *
 *  - frame:      `onboarding.<stepTag>.{title,primary,skip,back}`
 *                with stepTag ∈ welcome/identity/services/moderation/
 *                recoveryPhrase/done
 *  - services:   `onboarding.services.{recommended,custom}` cards;
 *                hub rows `onboarding.services.hub.{directory,
 *                messageDelivery,mediaDelivery,groupIntegrity}` +
 *                `onboarding.services.hub.done`
 *  - seat surfaces: `onboarding.services.<seat>.*` — directory TOFU
 *                `.confirm/.fingerprint/.pin/.added`; configured rows
 *                `.configured.<url>`; published notary rows
 *                `onboarding.services.groupIntegrity.published.<url>`
 *  - welcome:    restore entry `onboarding.welcome.restore` +
 *                overlay `onboarding.welcome.restore.{phrase_field,
 *                error,submit,cancel}` (iOS a11y-id parity)
 *  - recovery:   `onboarding.recoveryPhrase.{status,reveal}` <!-- onym:allow-secret-read -->
 *  - done:       `onboarding.done.{summary,backup_nudge}`
 *
 * Same style as [DiscoverySettingsScreenObject]: thin tag wrappers +
 * `await*` helpers that poll `onAllNodesWithTag` under
 * [ComposeContentTestRule.waitUntil].
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
        awaitTag("onboarding.${step.tag}.title")
        title(step).assertIsDisplayed()
        return this
    }

    fun tapPrimary(step: OnboardingStep): OnboardingScreenObject {
        primaryButton(step).performClick()
        return this
    }

    /** The outcome-gated steps (identity, recoveryPhrase) unlock their
     *  primary asynchronously — poll enablement before tapping. */
    fun awaitPrimaryEnabled(step: OnboardingStep): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = TIMEOUT) {
            rule.onAllNodesWithTag("onboarding.${step.tag}.primary")
                .fetchSemanticsNodes().isNotEmpty() &&
                isEnabled().matches(
                    rule.onNodeWithTag("onboarding.${step.tag}.primary")
                        .fetchSemanticsNode(),
                )
        }
        primaryButton(step).assertIsEnabled()
        return this
    }

    fun assertPrimaryDisabled(step: OnboardingStep): OnboardingScreenObject {
        primaryButton(step).assertIsNotEnabled()
        return this
    }

    fun assertPrimaryLabel(step: OnboardingStep, label: String): OnboardingScreenObject {
        primaryButton(step).assertTextEquals(label)
        return this
    }

    fun tapSkip(step: OnboardingStep): OnboardingScreenObject {
        skipButton(step).performClick()
        return this
    }

    fun assertNoSkip(step: OnboardingStep): OnboardingScreenObject {
        assertTrue(
            "step '${step.tag}' must not offer a skip affordance",
            rule.onAllNodesWithTag("onboarding.${step.tag}.skip")
                .fetchSemanticsNodes().isEmpty(),
        )
        return this
    }

    fun tapBack(step: OnboardingStep): OnboardingScreenObject {
        backButton(step).performClick()
        return this
    }

    fun assertStepAbsent(step: OnboardingStep): OnboardingScreenObject {
        assertTrue(
            "onboarding step '${step.tag}' unexpectedly on screen",
            rule.onAllNodesWithTag("onboarding.${step.tag}.title")
                .fetchSemanticsNodes().isEmpty(),
        )
        return this
    }

    fun assertNotShown(): OnboardingScreenObject {
        for (step in OnboardingStep.entries) assertStepAbsent(step)
        return this
    }

    // ─── services step: choice cards + hub ────────────────────────

    fun recommendedCard(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.recommended")

    fun customCard(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.custom")

    fun awaitRecommendedCard(): OnboardingScreenObject {
        awaitTag("onboarding.services.recommended")
        return this
    }

    /** Enter the "Choose services myself" hub. */
    fun tapCustomCard(): OnboardingScreenObject {
        customCard().performScrollTo().performClick()
        return this
    }

    fun hubRow(seat: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.hub.$seat")

    fun hubDoneButton(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.hub.done")

    fun awaitHub(): OnboardingScreenObject {
        awaitTag("onboarding.services.hub.done")
        return this
    }

    fun openHubRow(seat: String): OnboardingScreenObject {
        hubRow(seat).performScrollTo().performClick()
        return this
    }

    /** Close the hub back onto the services step frame. */
    fun tapHubDone(): OnboardingScreenObject {
        hubDoneButton().performClick()
        return this
    }

    /** Per-seat surface back affordance, returning to the hub. */
    fun tapSeatBack(seat: String): OnboardingScreenObject {
        rule.onNodeWithTag("onboarding.services.$seat.back").performClick()
        return this
    }

    // ─── directory seat: TOFU confirm ─────────────────────────────

    fun directoryConfirmButton(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.directory.confirm")

    fun directoryFingerprint(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.directory.fingerprint")

    fun directoryPinButton(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.directory.pin")

    fun directoryAddedState(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.directory.added")

    fun awaitDirectoryConfirm(): OnboardingScreenObject {
        awaitTag("onboarding.services.directory.confirm")
        return this
    }

    fun tapDirectoryConfirm(): OnboardingScreenObject {
        directoryConfirmButton().performScrollTo().performClick()
        return this
    }

    fun awaitDirectoryFingerprint(): OnboardingScreenObject {
        awaitTag("onboarding.services.directory.fingerprint")
        return this
    }

    fun assertDirectoryFingerprint(expected: String): OnboardingScreenObject {
        directoryFingerprint().assertTextEquals(expected)
        return this
    }

    fun tapDirectoryPin(): OnboardingScreenObject {
        directoryPinButton().performScrollTo().performClick()
        return this
    }

    fun awaitDirectoryAdded(): OnboardingScreenObject {
        awaitTag("onboarding.services.directory.added")
        return this
    }

    // ─── generic seat-surface rows ────────────────────────────────

    /** A configured (in-use) endpoint row on a seat surface, e.g.
     *  `onboarding.services.messageDelivery.configured.wss://…`. */
    fun configuredRow(seat: String, url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.$seat.configured.$url")

    fun awaitConfiguredRow(seat: String, url: String): OnboardingScreenObject {
        awaitTag("onboarding.services.$seat.configured.$url")
        return this
    }

    /** Fan-out semantics: Nostr relays have no per-row PRIMARY/BACKUP
     *  role chips. Cheap copy invariant on a configured row. */
    fun assertNoRoleChips(seat: String, url: String): OnboardingScreenObject {
        configuredRow(seat, url)
            .assert(hasNoDescendantWithText("PRIMARY"))
            .assert(hasNoDescendantWithText("BACKUP"))
        return this
    }

    private fun hasNoDescendantWithText(text: String): SemanticsMatcher =
        SemanticsMatcher("has no descendant with text '$text'") { node ->
            !hasAnyDescendant(hasText(text, substring = true)).matches(node)
        }

    /** The seeded first media-delivery row carries the ACTIVE chip. */
    fun assertActiveChip(seat: String, url: String): OnboardingScreenObject {
        configuredRow(seat, url)
            .assert(hasAnyDescendant(hasText("ACTIVE", substring = true)))
        return this
    }

    // ─── messageDelivery seat: catalog + module consent ───────────

    /** A pinned-provider catalog entry on the message-delivery
     *  surface, keyed by componentId (the hub port of Settings'
     *  `discovery.catalog.<seatType>.<componentId>` rows). */
    fun messageCatalogRow(componentId: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.messageDelivery.catalog.$componentId")

    fun awaitMessageCatalogRow(componentId: String): OnboardingScreenObject {
        awaitTag("onboarding.services.messageDelivery.catalog.$componentId")
        return this
    }

    fun openMessageCatalogRow(componentId: String): OnboardingScreenObject {
        messageCatalogRow(componentId).performScrollTo().performClick()
        return this
    }

    // Module consent runs through the EXISTING ModuleConsentScreen
    // (production `module_consent.*` tags — not part of the redesign
    // vocabulary).

    fun consentAcceptButton(): SemanticsNodeInteraction =
        rule.onNodeWithTag("module_consent.accept")

    /** Select an offer row (idempotent when already selected). */
    fun tapConsentOffer(offerId: String): OnboardingScreenObject {
        rule.onNodeWithTag("module_consent.offer.$offerId")
            .performScrollTo().performClick()
        return this
    }

    fun awaitConsentSheet(): OnboardingScreenObject {
        awaitTag("module_consent.accept")
        return this
    }

    fun awaitConsentAcceptEnabled(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = TIMEOUT) {
            rule.onAllNodesWithTag("module_consent.accept")
                .fetchSemanticsNodes().isNotEmpty() &&
                isEnabled().matches(consentAcceptButton().fetchSemanticsNode())
        }
        return this
    }

    fun tapConsentAccept(): OnboardingScreenObject {
        consentAcceptButton().performScrollTo().performClick()
        return this
    }

    fun awaitConsentDone(): OnboardingScreenObject {
        awaitTag("module_consent.done")
        return this
    }

    fun tapConsentDismiss(): OnboardingScreenObject {
        rule.onNodeWithTag("module_consent.done_button").performClick()
        return this
    }

    // ─── groupIntegrity seat: published notaries ──────────────────

    fun notaryPublishedRow(url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.groupIntegrity.published.$url")

    fun notaryAddButton(url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.services.groupIntegrity.published.add.$url")

    fun awaitNotaryPublishedRow(url: String): OnboardingScreenObject {
        awaitTag("onboarding.services.groupIntegrity.published.$url")
        return this
    }

    /** The published row's per-row Add affordance (the legacy add —
     *  no consent sheet). */
    fun tapNotaryAdd(url: String): OnboardingScreenObject {
        notaryAddButton(url).performScrollTo().performClick()
        return this
    }

    /** Footnote under the default RANDOM anchoring strategy — must
     *  name the random pick. Untagged copy (a plain SettingsFootnote),
     *  matched by its distinctive phrase. */
    fun assertNotaryFootnoteMentionsRandom(): OnboardingScreenObject {
        rule.onNodeWithText("at random", substring = true).assertIsDisplayed()
        return this
    }

    // ─── recoveryPhrase step content ──────────────────────────────

    fun recoveryStatus(): SemanticsNodeInteraction =
        // onym:allow-secret-read — UI test tag literal, not a mnemonic read
        rule.onNodeWithTag("onboarding.recoveryPhrase.status")

    fun recoveryRevealButton(): SemanticsNodeInteraction =
        // onym:allow-secret-read — UI test tag literal, not a mnemonic read
        rule.onNodeWithTag("onboarding.recoveryPhrase.reveal")

    fun awaitRecoveryStatus(): OnboardingScreenObject {
        // onym:allow-secret-read — UI test tag literal, not a mnemonic read
        awaitTag("onboarding.recoveryPhrase.status")
        return this
    }

    fun tapRecoveryReveal(): OnboardingScreenObject {
        recoveryRevealButton().performScrollTo().performClick()
        return this
    }

    // ─── recovery backup surface (RecoveryPhraseBackupScreen) ─────
    //
    // The reveal opens the existing backup screen full-screen
    // (`recovery.backup.*` tags). Its top-bar back is an untagged
    // IconButton found by content description ("Back") — unambiguous
    // because the walk frame's Back is a text button.

    fun awaitBackupIntro(): OnboardingScreenObject {
        awaitTag("recovery.backup.continue")
        return this
    }

    /** Intro Continue enables once the identity snapshot is ready. */
    fun awaitBackupIntroContinueEnabled(): OnboardingScreenObject {
        rule.waitUntil(timeoutMillis = TIMEOUT) {
            rule.onAllNodesWithTag("recovery.backup.continue")
                .fetchSemanticsNodes().isNotEmpty() &&
                isEnabled().matches(
                    rule.onNodeWithTag("recovery.backup.continue").fetchSemanticsNode(),
                )
        }
        return this
    }

    /** Continue → (faked) biometric gate → the reveal screen. */
    fun tapBackupIntroContinue(): OnboardingScreenObject {
        rule.onNodeWithTag("recovery.backup.continue").performClick()
        return this
    }

    fun awaitBackupRevealCard(): OnboardingScreenObject {
        awaitTag("recovery.backup.reveal")
        return this
    }

    /** Tap-to-reveal: the blur overlay goes away, the words show, and
     *  the flow records the step outcome. */
    fun tapBackupRevealCard(): OnboardingScreenObject {
        rule.onNodeWithTag("recovery.backup.reveal").performClick()
        return this
    }

    /** Leave the backup surface (no quiz) back onto the walk frame. */
    fun tapBackupBack(): OnboardingScreenObject {
        rule.onNodeWithContentDescription("Back").performClick()
        return this
    }

    // ─── done step content ────────────────────────────────────────

    fun doneSummary(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.done.summary")

    fun doneBackupNudge(): SemanticsNodeInteraction =
        rule.onNodeWithTag("onboarding.done.backup_nudge")

    fun awaitDoneSummary(): OnboardingScreenObject {
        awaitTag("onboarding.done.summary")
        return this
    }

    /** The summary's directory row in its PINNED state: the operator
     *  fingerprint is the checkable detail, and the explicit
     *  "Not confirmed" trailing must be absent. */
    fun assertDoneDirectoryPinned(fingerprint: String): OnboardingScreenObject {
        doneSummary()
            .assert(hasAnyDescendant(hasText(fingerprint)))
            .assert(hasNoDescendantWithText("Not confirmed"))
        return this
    }

    /** The summary's directory row in its UNPINNED state: explicit
     *  "Not confirmed" trailing, no fingerprint detail line. */
    fun assertDoneDirectoryNotConfirmed(fingerprint: String): OnboardingScreenObject {
        doneSummary()
            .assert(hasAnyDescendant(hasText("Not confirmed")))
            .assert(hasNoDescendantWithText(fingerprint))
        return this
    }

    // ─── after the walk ───────────────────────────────────────────

    /** The tab shell replaced the walk. */
    fun awaitTabShell(): OnboardingScreenObject {
        awaitTag("nav.tab.chats")
        return this
    }

    // ─── plumbing ─────────────────────────────────────────────────

    private fun awaitTag(tag: String) {
        rule.waitUntil(timeoutMillis = TIMEOUT) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        val TIMEOUT = 10.seconds.inWholeMilliseconds
    }
}
