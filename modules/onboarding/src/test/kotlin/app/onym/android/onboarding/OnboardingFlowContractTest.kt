package app.onym.android.onboarding

import app.onym.android.support.InMemoryOnboardingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the REDESIGNED [OnboardingFlow]:
 *
 *   Welcome → Identity → Services → (Moderation, reserved/disabled)
 *   → RecoveryPhrase → Done
 *
 * Written against the redesign contract (RECONCILIATION.md at the
 * repo root), API named per the existing OnboardingFlow.kt
 * conventions — StateFlow<State>, sealed [StepOutcome] (now with
 * [StepOutcome.Unavailable]), recordOutcome/advance/skip/back/
 * complete. New surface exercised here:
 *
 *  - `indicator(step): OnboardingFlow.Indicator?` — the step
 *    indicator covers ONLY the core steps (Identity, Services): null
 *    for welcome/recoveryPhrase/done, zero-based index of 2;
 *  - `requiresOutcomeToAdvance(step)` — the outcome gate matrix:
 *    identity + recoveryPhrase always, moderation only when
 *    enabled AND mandatory (directory non-empty / probe unresolved);
 *  - only recoveryPhrase is skippable;
 *  - services is SEEDED — outcomes[Services] starts Consented and
 *    advance() must not backfill NotApplicable over it;
 *  - `recordServicesChoice` / `recordRecoveryBackup` — sub-state the
 *    step content round-trips across back()/advance().
 */
class OnboardingFlowContractTest {

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

    /** Drive the walk to [target] recording whatever each gated step
     *  needs on the way. */
    private fun OnboardingFlow.walkTo(target: OnboardingStep) {
        while (step != target) {
            if (requiresOutcomeToAdvance(step) && !outcomeSatisfiesGate(step)) {
                recordOutcome(StepOutcome.Consented(componentId = null))
            }
            val before = step
            advance()
            check(step != before) { "walk stuck on $before before reaching $target" }
        }
    }

    // ── Step order ────────────────────────────────────────────────

    @Test
    fun stepOrder_moderationDisabled_omitsTheReservedSlot() {
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.Identity,
                OnboardingStep.Services,
                OnboardingStep.RecoveryPhrase,
                OnboardingStep.Done,
            ),
            flow(moderationEnabled = false).steps,
        )
    }

    @Test
    fun stepOrder_moderationEnabled_matchesIosSixSteps() {
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.Identity,
                OnboardingStep.Services,
                OnboardingStep.Moderation,
                OnboardingStep.RecoveryPhrase,
                OnboardingStep.Done,
            ),
            flow(moderationEnabled = true).steps,
        )
    }

    // ── Indicator coverage ────────────────────────────────────────

    @Test
    fun indicator_coversOnlyTheCoreSteps() {
        val flow = flow(moderationEnabled = false)
        // Unnumbered: the cover, the deferral-able backup, the outro.
        assertNull(flow.indicator(OnboardingStep.Welcome))
        assertNull(flow.indicator(OnboardingStep.RecoveryPhrase))
        assertNull(flow.indicator(OnboardingStep.Done))
        // Core: zero-based, out of the 2 core steps.
        assertEquals(
            OnboardingFlow.Indicator(index = 0, count = 2),
            flow.indicator(OnboardingStep.Identity),
        )
        assertEquals(
            OnboardingFlow.Indicator(index = 1, count = 2),
            flow.indicator(OnboardingStep.Services),
        )
    }

    @Test
    fun indicator_moderationEnabled_countsModerationAsCore() {
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

    // ── Skippability ──────────────────────────────────────────────

    @Test
    fun onlyRecoveryPhraseIsSkippable() {
        val flow = flow(moderationEnabled = false)
        for (step in flow.steps) {
            assertEquals(
                "isSkippable(${step.tag})",
                step == OnboardingStep.RecoveryPhrase,
                flow.isSkippable(step),
            )
        }
    }

    @Test
    fun skip_onRecoveryPhrase_recordsSkipped_andAdvances() {
        val flow = flow(moderationEnabled = false)
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.RecoveryPhrase))
    }

    @Test
    fun skip_onAnUnskippableStep_isANoOp() {
        val flow = flow(moderationEnabled = false)
        flow.skip() // welcome
        assertEquals(OnboardingStep.Welcome, flow.step)
        flow.advance()
        flow.skip() // identity — outcome-gated, never skippable
        assertEquals(OnboardingStep.Identity, flow.step)
        assertNull(flow.outcome(OnboardingStep.Identity))
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
    fun requiresOutcome_moderation_onlyWhenEnabledAndMandatory() = runTest {
        // Disabled: the step is out of the sequence entirely — the
        // gate is never consulted for a live step (the query itself
        // answers per [isMandatory] and is only meaningful for steps
        // in [OnboardingFlow.steps]).
        assertFalse(
            OnboardingStep.Moderation in flow(moderationEnabled = false).steps,
        )

        // Enabled, probe unresolved: fail-closed — gated.
        val unresolved = flow(moderationEnabled = true)
        assertTrue(unresolved.requiresOutcomeToAdvance(OnboardingStep.Moderation))

        // Enabled, directory non-empty: gated.
        val nonEmpty = flow(moderationEnabled = true, directoryNonEmpty = { true })
        nonEmpty.start()
        assertTrue(nonEmpty.requiresOutcomeToAdvance(OnboardingStep.Moderation))

        // Enabled, directory empty: nothing to mandate — not gated.
        val empty = flow(moderationEnabled = true, directoryNonEmpty = { false })
        empty.start()
        assertFalse(empty.requiresOutcomeToAdvance(OnboardingStep.Moderation))
    }

    // ── Advance refused without an outcome ────────────────────────

    @Test
    fun advance_onIdentity_refusedUntilTheBootstrapOutcomeLands() {
        val flow = flow(moderationEnabled = false)
        flow.advance() // welcome → identity
        assertEquals(OnboardingStep.Identity, flow.step)

        // No snapshot yet: the primary is a wall, not a door.
        flow.advance()
        assertEquals(OnboardingStep.Identity, flow.step)

        // The bootstrap reports; now the walk moves.
        flow.recordOutcome(StepOutcome.Consented(componentId = null))
        flow.advance()
        assertEquals(OnboardingStep.Services, flow.step)
    }

    @Test
    fun advance_onRecoveryPhrase_refusedUntilTheRevealOutcomeLands() {
        val flow = flow(moderationEnabled = false)
        flow.walkTo(OnboardingStep.RecoveryPhrase)

        flow.advance()
        assertEquals(
            "advance must refuse before the phrase was revealed",
            OnboardingStep.RecoveryPhrase,
            flow.step,
        )

        flow.recordOutcome(StepOutcome.Consented(componentId = null))
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test
    fun skipThenBack_reLocksTheGate_untilConsentedOverwrites() {
        // The outcomeSatisfiesGate rule: a surviving Skipped outcome
        // is a decision to DEFER, not the thing the gate proves — so
        // skip → Done → back leaves the recovery step re-locked, and
        // the honest exits are the reveal (Consented) or skipping
        // again.
        val flow = flow(moderationEnabled = false)
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)

        flow.back()
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.RecoveryPhrase))
        assertFalse(flow.outcomeSatisfiesGate(OnboardingStep.RecoveryPhrase))
        flow.advance()
        assertEquals(
            "advance must refuse over a surviving Skipped outcome",
            OnboardingStep.RecoveryPhrase,
            flow.step,
        )

        // Skipping again is still allowed…
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
        flow.back()

        // …and so is the reveal: Consented overwrites and unlocks.
        flow.recordOutcome(StepOutcome.Consented(componentId = null))
        assertTrue(flow.outcomeSatisfiesGate(OnboardingStep.RecoveryPhrase))
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test
    fun advance_gatedStep_acceptsUnavailableAsARecordedOutcome() {
        // A bootstrap that failed terminally still RECORDED — the gate
        // is on "content reported", not on success.
        val flow = flow(moderationEnabled = false)
        flow.advance() // → identity
        flow.recordOutcome(StepOutcome.Unavailable)
        flow.advance()
        assertEquals(OnboardingStep.Services, flow.step)
        assertEquals(StepOutcome.Unavailable, flow.outcome(OnboardingStep.Identity))
    }

    // ── Seeded services ───────────────────────────────────────────

    @Test
    fun services_isSeeded_consentedFromConstruction() {
        val flow = flow(moderationEnabled = false)
        assertTrue(
            "services must start with a seeded Consented outcome",
            flow.outcome(OnboardingStep.Services) is StepOutcome.Consented,
        )
    }

    @Test
    fun advance_pastServices_doesNotOverwriteTheSeededOutcome() {
        val flow = flow(moderationEnabled = false)
        val seeded = flow.outcome(OnboardingStep.Services)
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        assertEquals(
            "advance must not backfill NotApplicable over the seed",
            seeded,
            flow.outcome(OnboardingStep.Services),
        )
    }

    // ── Sub-state persistence across back/forward ─────────────────

    @Test
    fun servicesChoice_survivesBackAndForward() {
        val flow = flow(moderationEnabled = false)
        flow.walkTo(OnboardingStep.Services)
        assertEquals(ServicesChoice.Recommended, flow.state.value.servicesChoice)

        flow.recordServicesChoice(ServicesChoice.Custom)
        flow.back() // → identity
        assertEquals(OnboardingStep.Identity, flow.step)
        assertEquals(ServicesChoice.Custom, flow.state.value.servicesChoice)

        flow.advance() // → services (identity outcome already recorded)
        assertEquals(OnboardingStep.Services, flow.step)
        assertEquals(ServicesChoice.Custom, flow.state.value.servicesChoice)
    }

    @Test
    fun recoveryBackupState_survivesBackAndForward() {
        val flow = flow(moderationEnabled = false)
        flow.walkTo(OnboardingStep.RecoveryPhrase)
        assertEquals(RecoveryBackupState.None, flow.state.value.recoveryBackupState)

        flow.recordRecoveryBackup(RecoveryBackupState.Revealed)
        flow.recordOutcome(StepOutcome.Consented(componentId = null))
        flow.back() // → services
        assertEquals(RecoveryBackupState.Revealed, flow.state.value.recoveryBackupState)

        flow.advance() // → recoveryPhrase again
        assertEquals(OnboardingStep.RecoveryPhrase, flow.step)
        assertEquals(RecoveryBackupState.Revealed, flow.state.value.recoveryBackupState)
        // And the prior outcome is kept — the primary stays unlocked.
        assertEquals(
            StepOutcome.Consented(componentId = null),
            flow.outcome(OnboardingStep.RecoveryPhrase),
        )
    }

    // ── Completion ────────────────────────────────────────────────

    @Test
    fun complete_onlyFromDone_persistsTheFlagOnce() = runTest {
        val store = InMemoryOnboardingStore()
        val flow = flow(store = store)

        flow.complete() // welcome: guarded no-op
        assertFalse(store.completed)

        flow.walkTo(OnboardingStep.RecoveryPhrase)
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
        flow.complete()
        assertTrue(store.completed)
        assertTrue(flow.state.value.completed)
        assertEquals(1, store.markCount)
    }
}
