package app.onym.android.onboarding

import app.onym.android.support.InMemoryOnboardingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnboardingFlow] state machine: step order for both moderation
 * flags, the review-hardened gating rules ported from onym-ios
 * PR #249 (no advance-bypass around a mandatory step, fail-closed
 * unresolved probe, complete() only from Done), and outcome
 * bookkeeping across back().
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

    // ── Step order ────────────────────────────────────────────────

    @Test
    fun stepOrder_moderationDisabled_omitsTheReservedSlot() {
        val flow = flow(moderationEnabled = false)
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.DiscoveryConfirm,
                OnboardingStep.MessageTransport,
                OnboardingStep.BlobTransport,
                OnboardingStep.Notary,
                OnboardingStep.Done,
            ),
            flow.steps,
        )
        assertEquals(6, flow.stepCount)
    }

    @Test
    fun stepOrder_moderationEnabled_matchesIosSevenSteps() {
        val flow = flow(moderationEnabled = true)
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.DiscoveryConfirm,
                OnboardingStep.MessageTransport,
                OnboardingStep.BlobTransport,
                OnboardingStep.Notary,
                OnboardingStep.Moderation,
                OnboardingStep.Done,
            ),
            flow.steps,
        )
        assertEquals(7, flow.stepCount)
    }

    @Test
    fun advance_walksTheWholeDisabledSequence_withoutEverBlocking() {
        val flow = flow(moderationEnabled = false)
        // With moderation absent there are no mandatory steps — the
        // primary button alone walks welcome → done.
        repeat(flow.stepCount - 1) { flow.advance() }
        assertEquals(OnboardingStep.Done, flow.step)
        // Terminal: advance on Done is a no-op.
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test
    fun moderationDisabled_noStepIsEverMandatory() {
        val flow = flow(moderationEnabled = false)
        flow.steps.forEach { step ->
            assertFalse("$step must not be mandatory", flow.isMandatory(step))
        }
    }

    @Test
    fun stepIndex_tracksThePositionInTheActiveSequence() {
        val flow = flow(moderationEnabled = false)
        assertEquals(0, flow.stepIndex)
        flow.advance()
        assertEquals(1, flow.stepIndex)
    }

    // ── Skippability ──────────────────────────────────────────────

    @Test
    fun welcome_isUnskippable() {
        val flow = flow()
        assertFalse(flow.isSkippable(OnboardingStep.Welcome))
        flow.skip()
        assertEquals(OnboardingStep.Welcome, flow.step)
        assertNull(flow.outcome(OnboardingStep.Welcome))
    }

    @Test
    fun done_isUnskippable() {
        val flow = flow()
        repeat(flow.stepCount - 1) { flow.advance() }
        assertEquals(OnboardingStep.Done, flow.step)
        assertFalse(flow.isSkippable(OnboardingStep.Done))
        flow.skip()
        assertEquals(OnboardingStep.Done, flow.step)
    }

    @Test
    fun middleSteps_areSkippable_recordingSkipped() {
        val flow = flow()
        flow.advance() // welcome → discoveryConfirm
        assertTrue(flow.isSkippable(OnboardingStep.DiscoveryConfirm))
        flow.skip()
        assertEquals(OnboardingStep.MessageTransport, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.DiscoveryConfirm))
    }

    // ── Moderation probe: tri-state, fail-closed ──────────────────

    @Test
    fun probeUnresolved_failsClosed_mandatoryAndUnskippable() {
        // start() never called — the probe stays null.
        val flow = flow(moderationEnabled = true)
        assertFalse(flow.state.value.moderationProbeResolved)
        assertTrue(flow.isMandatory(OnboardingStep.Moderation))
        assertFalse(flow.isSkippable(OnboardingStep.Moderation))
    }

    @Test
    fun probeTrue_moderationIsMandatoryAndUnskippable() = runTest {
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { true })
        flow.start()
        assertEquals(true, flow.state.value.moderationDirectoryHasEntries)
        assertTrue(flow.isMandatory(OnboardingStep.Moderation))
        assertFalse(flow.isSkippable(OnboardingStep.Moderation))
    }

    @Test
    fun probeFalse_moderationIsSkippableAndNotMandatory() = runTest {
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { false })
        flow.start()
        assertEquals(false, flow.state.value.moderationDirectoryHasEntries)
        assertFalse(flow.isMandatory(OnboardingStep.Moderation))
        assertTrue(flow.isSkippable(OnboardingStep.Moderation))
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

    // ── Mandatory gating (generic, exercised via moderation) ──────

    @Test
    fun advance_onMandatoryStepWithoutOutcome_isANoOp() = runTest {
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { true })
        flow.start()
        repeat(5) { flow.advance() } // welcome … notary → moderation
        assertEquals(OnboardingStep.Moderation, flow.step)

        // No outcome recorded: neither the primary path nor skip may
        // walk past a mandatory step.
        flow.advance()
        assertEquals(OnboardingStep.Moderation, flow.step)
        flow.skip()
        assertEquals(OnboardingStep.Moderation, flow.step)

        // The step content reports a consent — now advance proceeds.
        flow.recordOutcome(StepOutcome.Consented("onym:component:onym-moderation"))
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
        assertEquals(
            StepOutcome.Consented("onym:component:onym-moderation"),
            flow.outcome(OnboardingStep.Moderation),
        )
    }

    @Test
    fun advance_onMandatoryStepWithUnresolvedProbe_isANoOp() {
        val flow = flow(moderationEnabled = true)
        repeat(5) { flow.advance() }
        assertEquals(OnboardingStep.Moderation, flow.step)
        // Probe never resolved — fail closed, no advance without an
        // outcome, no skip at all.
        flow.advance()
        flow.skip()
        assertEquals(OnboardingStep.Moderation, flow.step)
    }

    @Test
    fun emptyDirectory_moderationAdvancesAsInformational() = runTest {
        val flow = flow(moderationEnabled = true, directoryNonEmpty = { false })
        flow.start()
        repeat(5) { flow.advance() }
        assertEquals(OnboardingStep.Moderation, flow.step)
        flow.advance()
        assertEquals(OnboardingStep.Done, flow.step)
        assertEquals(StepOutcome.NotApplicable, flow.outcome(OnboardingStep.Moderation))
    }

    // ── Outcomes ──────────────────────────────────────────────────

    @Test
    fun advance_backfillsNotApplicable_onlyWhenNothingWasRecorded() {
        val flow = flow()
        flow.advance() // welcome: backfilled
        assertEquals(StepOutcome.NotApplicable, flow.outcome(OnboardingStep.Welcome))

        flow.recordOutcome(StepOutcome.Consented(null))
        flow.advance() // discoveryConfirm: recorded consent kept
        assertEquals(StepOutcome.Consented(null), flow.outcome(OnboardingStep.DiscoveryConfirm))
    }

    @Test
    fun recordOutcome_overwritesAPriorRecord() {
        val flow = flow()
        flow.advance()
        flow.recordOutcome(StepOutcome.Consented("onym:component:a"))
        flow.recordOutcome(StepOutcome.Consented("onym:component:b"))
        assertEquals(
            StepOutcome.Consented("onym:component:b"),
            flow.outcome(OnboardingStep.DiscoveryConfirm),
        )
    }

    @Test
    fun back_preservesTheRevisitedStepsOutcome() {
        val flow = flow()
        flow.advance() // welcome → discoveryConfirm
        flow.skip() // → messageTransport, discoveryConfirm = Skipped
        flow.back() // → discoveryConfirm
        assertEquals(OnboardingStep.DiscoveryConfirm, flow.step)
        assertEquals(StepOutcome.Skipped, flow.outcome(OnboardingStep.DiscoveryConfirm))

        // Redoing the step overwrites the preserved outcome.
        flow.recordOutcome(StepOutcome.Consented(null))
        flow.advance()
        assertEquals(StepOutcome.Consented(null), flow.outcome(OnboardingStep.DiscoveryConfirm))
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
        flow.advance() // discoveryConfirm — not Done
        flow.complete()
        assertFalse(store.completed)
        assertFalse(flow.state.value.completed)
    }

    @Test
    fun complete_onDone_writesTheFlagAndPublishesCompleted() = runTest {
        val store = InMemoryOnboardingStore()
        val flow = flow(store = store)
        repeat(flow.stepCount - 1) { flow.advance() }
        assertEquals(OnboardingStep.Done, flow.step)

        flow.complete()
        assertTrue(store.completed)
        assertEquals(1, store.markCount)
        assertTrue(flow.state.value.completed)
        assertEquals(StepOutcome.NotApplicable, flow.outcome(OnboardingStep.Done))
    }
}
