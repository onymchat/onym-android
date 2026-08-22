package app.onym.android.onboarding

import app.onym.android.support.InMemoryOnboardingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnboardingFlow] state machine, redesigned model (onym-ios PR
 * #259 parity): step order for both moderation flags, the indicator
 * coverage over the core steps, only-recovery skippability, the
 * requiresOutcomeToAdvance matrix (identity / recovery / mandatory
 * moderation), the seeded services outcome, flow-held
 * servicesChoice / recoveryBackupState, and outcome bookkeeping
 * across back().
 */
class OnboardingFlowTest {

    private fun flow(
        store: InMemoryOnboardingStore = InMemoryOnboardingStore(),
        moderationEnabled: Boolean = false,
        directoryNonEmpty: suspend () -> Boolean = { true },
    ) = OnboardingFlow(
        store = store,
        moderationEnabled = moderationEnabled,
        moderationDirectoryNonEmpty = directoryNonEmpty,
    )

    private val OnboardingFlow.step: OnboardingStep get() = state.value.step
    private fun OnboardingFlow.outcome(step: OnboardingStep): StepOutcome? =
        state.value.outcomes[step]

    /** Walk the flow to [target], recording what the gated steps need. */
    private fun OnboardingFlow.walkTo(target: OnboardingStep) {
        while (step != target) {
            when (step) {
                OnboardingStep.Identity ->
                    if (outcome(OnboardingStep.Identity) == null) {
                        recordOutcome(StepOutcome.NotApplicable)
                    }
                OnboardingStep.RecoveryPhrase -> {
                    skip()
                    continue
                }
                else -> Unit
            }
            val before = step
            advance()
            check(step != before) { "stuck on $before walking to $target" }
        }
    }

    // ── Step order ────────────────────────────────────────────────

    @Test
    fun stepOrder_moderationDisabled_omitsTheReservedSlot() {
        val flow = flow(moderationEnabled = false)
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.Identity,
                OnboardingStep.Services,
                OnboardingStep.RecoveryPhrase,
                OnboardingStep.Done,
            ),
            flow.steps,
        )
    }

    @Test
    fun stepOrder_moderationEnabled_matchesIosSixSteps() {
        val flow = flow(moderationEnabled = true)
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.Identity,
                OnboardingStep.Services,
                OnboardingStep.Moderation,
                OnboardingStep.RecoveryPhrase,
                OnboardingStep.Done,
            ),
            flow.steps,
        )
    }

    // ── Indicator coverage ────────────────────────────────────────

    @Test
    fun indicator_coversExactlyTheCoreSteps_moderationDisabled() {
        val flow = flow(moderationEnabled = false)
        assertEquals(
            OnboardingFlow.Indicator(index = 0, count = 2),
            flow.indicator(OnboardingStep.Identity),
        )
        assertEquals(
            OnboardingFlow.Indicator(index = 1, count = 2),
            flow.indicator(OnboardingStep.Services),
        )
        // Unnumbered steps carry no indicator — the scaffold must not
        // reserve blank indicator space on them.
        assertNull(flow.indicator(OnboardingStep.Welcome))
        assertNull(flow.indicator(OnboardingStep.RecoveryPhrase))
        assertNull(flow.indicator(OnboardingStep.Done))
        // The reserved slot isn't counted while disabled.
        assertNull(flow.indicator(OnboardingStep.Moderation))
    }

    @Test
    fun indicator_countsModeration_whenEnabled() {
        val flow = flow(moderationEnabled = true)
        assertEquals(
            OnboardingFlow.Indicator(index = 0, count = 3),
            flow.indicator(OnboardingStep.Identity),
        )
        assertEquals(
            OnboardingFlow.Indicator(index = 1, count = 3),
            flow.indicator(OnboardingStep.Services),
        )
        assertEquals(
            OnboardingFlow.Indicator(index = 2, count = 3),
            flow.indicator(OnboardingStep.Moderation),
        )
        assertNull(flow.indicator(OnboardingStep.Welcome))
        assertNull(flow.indicator(OnboardingStep.RecoveryPhrase))
        assertNull(flow.indicator(OnboardingStep.Done))
    }

    // ── Skippability: only the recovery phrase ────────────────────

    @Test
    fun onlyRecoveryPhrase_isSkippable() {
        val flow = flow(moderationEnabled = true)
        OnboardingStep.entries.forEach { step ->
            if (step == OnboardingStep.RecoveryPhrase) {
                assertTrue(flow.isSkippable(step))
            } else {
                assertFalse("$step must not be skippable", flow.isSkippable(step))
            }
        }
    }

    @Test
    fun skip_onUnskippableStep_isANoOp() {
        val flow = flow()
        flow.skip()
        assertEquals(OnboardingStep.Welcome, flow.step)
        assertNull(flow.outcome(OnboardingStep.Welcome))
    }

    @Test
    fun skip_onRecoveryPhrase_recordsSkipped() {
        val flow = flow()
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.RecoveryPhrase))
    }

    // ── requiresOutcomeToAdvance matrix ───────────────────────────

    @Test
    fun requiresOutcome_identityAndRecovery_always() {
        val flow = flow(moderationEnabled = false)
        assertTrue(flow.requiresOutcomeToAdvance(OnboardingStep.Identity))
        assertTrue(flow.requiresOutcomeToAdvance(OnboardingStep.RecoveryPhrase))
        assertFalse(flow.requiresOutcomeToAdvance(OnboardingStep.Welcome))
        assertFalse(flow.requiresOutcomeToAdvance(OnboardingStep.Services))
        assertFalse(flow.requiresOutcomeToAdvance(OnboardingStep.Done))
    }

    @Test
    fun requiresOutcome_moderation_failsClosedUntilTheProbeAnswersEmpty() = runTest {
        // Unresolved probe: fail-closed, moderation is gated.
        val unresolved = flow(moderationEnabled = true)
        assertTrue(unresolved.requiresOutcomeToAdvance(OnboardingStep.Moderation))

        // Resolved non-empty: still gated (the user must consent).
        val nonEmpty = flow(moderationEnabled = true, directoryNonEmpty = { true })
        nonEmpty.start()
        assertTrue(nonEmpty.requiresOutcomeToAdvance(OnboardingStep.Moderation))

        // Resolved EMPTY: Continue-only informational.
        val empty = flow(moderationEnabled = true, directoryNonEmpty = { false })
        empty.start()
        assertFalse(empty.requiresOutcomeToAdvance(OnboardingStep.Moderation))
    }

    @Test
    fun advance_onIdentityWithoutOutcome_isANoOp() {
        val flow = flow()
        flow.advance() // welcome → identity
        assertEquals(OnboardingStep.Identity, flow.step)

        // No outcome yet (the bootstrap hasn't produced a snapshot):
        // the primary path must not walk past — the fail-closed dead
        // end is intentional.
        flow.advance()
        assertEquals(OnboardingStep.Identity, flow.step)

        // The step content reports the snapshot — now advance
        // proceeds.
        flow.recordOutcome(StepOutcome.NotApplicable)
        flow.advance()
        assertEquals(OnboardingStep.Services, flow.step)
    }

    @Test
    fun advance_onRecoveryPhraseWithoutOutcome_isANoOp() {
        val flow = flow()
        flow.walkTo(OnboardingStep.Services)
        flow.advance() // services → recoveryPhrase (seeded outcome)
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)

        // "I've written it down" must not be a back door around the
        // must-reveal rule.
        flow.advance()
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)

        // The reveal records the outcome — now advance proceeds.
        flow.recordOutcome(StepOutcome.Consented(null))
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test
    fun advance_onMandatoryModerationWithoutOutcome_isANoOp() = runTest {
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { true })
        flow.start()
        flow.walkTo(OnboardingStep.Moderation)

        flow.advance()
        assertEquals(OnboardingStep.Moderation, flow.step)
        flow.skip()
        assertEquals(OnboardingStep.Moderation, flow.step)

        flow.recordOutcome(StepOutcome.Consented("onym:component:onym-moderation"))
        flow.advance()
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)
    }

    @Test
    fun emptyDirectory_moderationAdvances_recordingUnavailable() = runTest {
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { false })
        flow.start()
        flow.walkTo(OnboardingStep.Moderation)
        flow.advance()
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)
        // NOT Skipped and NOT NotApplicable: moderation wasn't
        // offered; the user did not opt out.
        assertEquals(StepOutcome.Unavailable, flow.outcome(OnboardingStep.Moderation))
    }

    // ── Moderation probe: tri-state, fail-closed ──────────────────

    @Test
    fun probeUnresolved_failsClosed() {
        val flow = flow(moderationEnabled = true)
        assertFalse(flow.state.value.moderationProbeResolved)
        assertTrue(flow.isMandatory(OnboardingStep.Moderation))
    }

    @Test
    fun probeResolution_drivesMandatory() = runTest {
        val nonEmpty = flow(moderationEnabled = true, directoryNonEmpty = { true })
        nonEmpty.start()
        assertTrue(nonEmpty.isMandatory(OnboardingStep.Moderation))

        val empty = flow(moderationEnabled = true, directoryNonEmpty = { false })
        empty.start()
        assertFalse(empty.isMandatory(OnboardingStep.Moderation))
    }

    @Test
    fun start_isIdempotent_probeRunsOnce() = runTest {
        var probes = 0
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { probes += 1; true })
        flow.start()
        flow.start()
        assertEquals(1, probes)
    }

    @Test
    fun start_withModerationDisabled_neverRunsTheProbe() = runTest {
        var probes = 0
        val flow = flow(moderationEnabled = false, directoryNonEmpty = { probes += 1; true })
        flow.start()
        assertEquals(0, probes)
        assertNull(flow.state.value.moderationDirectoryHasEntries)
    }

    // ── Services outcome: seeded, overwritten ─────────────────────

    @Test
    fun servicesOutcome_isSeededConsented_fromConstruction() {
        // The recommended card shows "Selected" from the first frame,
        // so Continue from the services step IS accepting it — the
        // outcome must say the same thing before any interaction.
        val flow = flow()
        assertEquals(StepOutcome.Consented(null), flow.outcome(OnboardingStep.Services))
    }

    @Test
    fun servicesOutcome_hubComponentConsent_overwritesTheSeed() {
        val flow = flow()
        flow.walkTo(OnboardingStep.Services)
        flow.recordOutcome(StepOutcome.Consented("onym:component:custom-relay"))
        assertEquals(
            StepOutcome.Consented("onym:component:custom-relay"),
            flow.outcome(OnboardingStep.Services),
        )
        // Last-consent-wins across the hub's seats: a later consent
        // (another seat, or re-accepting the recommendation)
        // overwrites — arbitrary but harmless, the Done summary reads
        // live repository state.
        flow.recordOutcome(StepOutcome.Consented(null))
        assertEquals(StepOutcome.Consented(null), flow.outcome(OnboardingStep.Services))
    }

    @Test
    fun servicesChoice_isFlowHeld_survivingBackAndForward() {
        val flow = flow()
        assertEquals(ServicesChoice.Recommended, flow.state.value.servicesChoice)
        flow.walkTo(OnboardingStep.Services)
        flow.recordServicesChoice(ServicesChoice.Custom)
        // Navigate away and back — the choice must not reset (it
        // lives on the flow, not on any composable).
        flow.back()
        flow.advance()
        assertEquals(OnboardingStep.Services, flow.step)
        assertEquals(ServicesChoice.Custom, flow.state.value.servicesChoice)
    }

    // ── Recovery backup state: never downgrades ───────────────────

    @Test
    fun recoveryBackupState_neverDowngrades() {
        val flow = flow()
        assertEquals(RecoveryBackupState.None, flow.state.value.recoveryBackupState)

        flow.recordRecoveryBackup(RecoveryBackupState.Revealed)
        assertEquals(RecoveryBackupState.Revealed, flow.state.value.recoveryBackupState)

        flow.recordRecoveryBackup(RecoveryBackupState.Verified)
        assertEquals(RecoveryBackupState.Verified, flow.state.value.recoveryBackupState)

        // A re-reveal after verifying must not downgrade.
        flow.recordRecoveryBackup(RecoveryBackupState.Revealed)
        assertEquals(RecoveryBackupState.Verified, flow.state.value.recoveryBackupState)
        flow.recordRecoveryBackup(RecoveryBackupState.None)
        assertEquals(RecoveryBackupState.Verified, flow.state.value.recoveryBackupState)
    }

    @Test
    fun recoveryBackupState_survivesNavigation() {
        val flow = flow()
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.recordRecoveryBackup(RecoveryBackupState.Revealed)
        flow.recordOutcome(StepOutcome.Consented(null))
        flow.advance() // → done
        flow.back() // → recoveryPhrase
        assertEquals(RecoveryBackupState.Revealed, flow.state.value.recoveryBackupState)
    }

    // ── Identity origin (welcome-step restore) ────────────────────

    @Test
    fun identityOrigin_defaultsToMinted() {
        assertEquals(IdentityOrigin.Minted, flow().state.value.identityOrigin)
    }

    @Test
    fun neverLeftWelcome_isFalseOnceAdvanced_evenAfterBack() {
        val flow = flow()
        // Seeded outcomes make `outcomes.isEmpty()` useless as this
        // signal — the property must key off Welcome specifically.
        assertFalse(flow.state.value.outcomes.isEmpty())
        assertTrue(flow.state.value.neverLeftWelcome)

        flow.advance() // welcome → identity, backfills Welcome
        assertFalse(flow.state.value.neverLeftWelcome)

        // The case the restore gate exists for: the user walks on,
        // configures seats, and comes BACK to Welcome. The identity
        // is no longer safe to swap out from under that state.
        flow.back()
        assertEquals(OnboardingStep.Welcome, flow.step)
        assertFalse(flow.state.value.neverLeftWelcome)
    }

    @Test
    fun identityOrigin_recordedRestored_survivesNavigation() {
        val flow = flow()
        flow.recordIdentityOrigin(IdentityOrigin.Restored)
        assertEquals(IdentityOrigin.Restored, flow.state.value.identityOrigin)

        // The copy that depends on it renders on the identity and
        // recovery steps — it must survive the whole walk, Back
        // included.
        flow.walkTo(OnboardingStep.Done)
        flow.back()
        assertEquals(IdentityOrigin.Restored, flow.state.value.identityOrigin)
    }

    // ── Outcomes across navigation ────────────────────────────────

    @Test
    fun advance_backfillsNotApplicable_onlyWhenNothingWasRecorded() {
        val flow = flow()
        flow.advance() // welcome: backfilled
        assertEquals(StepOutcome.NotApplicable, flow.outcome(OnboardingStep.Welcome))
    }

    @Test
    fun back_preservesOutcome_untilOverwritten() {
        val flow = flow()
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.skip() // → done, recoveryPhrase = Skipped
        flow.back() // → recoveryPhrase
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.RecoveryPhrase))

        // The surviving Skipped does NOT satisfy the gate — the
        // primary must read disabled and advance() must refuse:
        // "I've written it down" can't ride on a deferral.
        assertFalse(flow.outcomeSatisfiesGate(OnboardingStep.RecoveryPhrase))
        flow.advance()
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)

        // Redoing the step (the reveal) overwrites the preserved
        // outcome and satisfies the gate.
        flow.recordOutcome(StepOutcome.Consented(null))
        assertTrue(flow.outcomeSatisfiesGate(OnboardingStep.RecoveryPhrase))
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
        assertEquals(
            StepOutcome.Consented(null),
            flow.outcome(OnboardingStep.RecoveryPhrase),
        )
    }

    @Test
    fun skipThenBack_reGatesThePrimary() {
        // Regression: RecoveryPhrase → "Remind me later" → Done →
        // Back left the primary enabled off the surviving Skipped
        // outcome, letting "I've written it down" advance with the
        // phrase never revealed. A Skipped outcome must not satisfy
        // an outcome-gated step; the honest exits after skip+back
        // are the reveal (records Consented) or skipping again.
        val flow = flow()
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
        flow.back()
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)

        assertFalse(flow.outcomeSatisfiesGate(OnboardingStep.RecoveryPhrase))
        flow.advance()
        assertEquals(
            "advance must refuse on a skipped-then-revisited gated step",
            OnboardingStep.RecoveryPhrase,
            flow.step,
        )

        // Skipping again is still available.
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.RecoveryPhrase))
    }

    @Test
    fun back_onWelcome_isANoOp() {
        val flow = flow()
        flow.back()
        assertEquals(OnboardingStep.Welcome, flow.step)
    }

    // ── Completion ────────────────────────────────────────────────

    @Test
    fun complete_midSequence_isANoOp_andNeverWritesTheFlag() = runTest {
        val store = InMemoryOnboardingStore()
        val flow = flow(store = store)
        flow.advance() // identity — not Done
        flow.complete()
        assertFalse(store.completed)
        assertFalse(flow.state.value.completed)
    }

    @Test
    fun complete_onDone_writesTheFlagAndPublishesCompleted() = runTest {
        val store = InMemoryOnboardingStore()
        val flow = flow(store = store)
        flow.walkTo(OnboardingStep.Done)

        flow.complete()
        assertTrue(store.completed)
        assertEquals(1, store.markCount)
        assertTrue(flow.state.value.completed)
        assertEquals(StepOutcome.NotApplicable, flow.outcome(OnboardingStep.Done))
    }

    @Test
    fun advance_onDone_isANoOp() {
        val flow = flow()
        flow.walkTo(OnboardingStep.Done)
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
    }
}
