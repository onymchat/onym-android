package app.onym.android.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The first-launch steps, in presentation order. [tag] is the stable
 * lowerCamel identifier used in test tags
 * (`onboarding.<tag>.<element>`) — decoupled from the enum constant
 * name so renames can't silently break UI tests.
 *
 * The user-facing story is "step N of M" over the CORE steps —
 * identity and services (plus moderation when enabled) — bracketed by
 * an unnumbered welcome, the recovery-phrase backup, and the closing
 * summary. [OnboardingFlow.indicator] reflects that: only the core
 * steps carry a position.
 *
 * [Moderation] is RESERVED: the moderation seat has no Android
 * implementation yet, so the step only enters the sequence when
 * [OnboardingFlow] is constructed with `moderationEnabled = true`
 * (future parity with onym-ios, whose redesigned flow walks all six
 * steps).
 */
enum class OnboardingStep(val tag: String) {
    Welcome("welcome"),

    /** "Making your keys" — identity creation status. Informational
     *  but outcome-gated: the content records only once the identity
     *  bootstrap actually yields a snapshot. */
    Identity("identity"),

    /** "Your services" — the recommended-setup card, with the
     *  "choose services myself" hub as an in-step branch. Collapses
     *  the old discoveryConfirm / messageTransport / blobTransport /
     *  notary wizard steps. */
    Services("services"),

    /** RESERVED — see the enum KDoc. */
    Moderation("moderation"),

    /** "Save your recovery phrase" — the backup reveal, promoted from
     *  the old Done-step footnote nudge. The only skippable step
     *  ("Remind me later"). */
    RecoveryPhrase("recoveryPhrase"),

    Done("done"),
}

/**
 * What happened at one step, modeled abstractly — the flow never
 * links against the modules whose surfaces produce these outcomes
 * (:discovery's module consent). The app-layer step content reports
 * back through this type.
 */
sealed interface StepOutcome {
    /**
     * The user consented at this step. [componentId] names the chosen
     * component when the step's surface knows one (catalog picks);
     * null for consents without a component identity (accepting the
     * recommended setup, confirming a source, backing up the phrase).
     */
    data class Consented(val componentId: String?) : StepOutcome

    /** The user skipped the step. Only the recovery-phrase step is
     *  skippable now ("Remind me later") — Settings keeps nudging
     *  until backed up. */
    data object Skipped : StepOutcome

    /** The step had nothing to decide (informational steps: welcome,
     *  identity, done). */
    data object NotApplicable : StepOutcome

    /**
     * The step's obligation exists but could not be offered — today
     * exactly moderation with an EMPTY authority directory: there is
     * no authority to pick, so the user acknowledges with Continue.
     * Distinct from [Skipped] (a choice the user declined to make)
     * and [NotApplicable] (nothing to decide at all) so downstream
     * state never mistakes "moderation wasn't available" for "the
     * user opted out" — opting out of moderation is not a thing.
     * Mirrors `StepOutcome.unavailable` from onym-ios.
     */
    data object Unavailable : StepOutcome
}

/**
 * How far the recovery-phrase backup got. Lives on the flow's [OnboardingFlow.State]
 * (not on any composable) for the same reason as [ServicesChoice]:
 * the step body is rebuilt on every navigation, and the status card
 * must not reset to "Not backed up yet" after Back from Done. Also
 * what lets the Done summary tell "saw the words" apart from
 * "verified them". Ordered so "never downgrades" is an ordinal
 * comparison.
 */
enum class RecoveryBackupState {
    /** The phrase was never shown. */
    None,

    /** The words were on screen — enough to honestly tap "I've
     *  written it down", not enough to claim a verified backup. */
    Revealed,

    /** The reveal + verify quiz completed. */
    Verified,
}

/**
 * Which path the services step is on. Lives on the flow (not the step
 * view) so the selection survives Back/forward navigation — the step
 * body derives its "Selected" chip from this, never from view-local
 * state.
 */
enum class ServicesChoice {
    /** The preselected default: seeded services plus the published
     *  lists installed on completion. */
    Recommended,

    /** The user went through the "choose services myself" hub. */
    Custom,
}

/**
 * State machine for the first-launch onboarding sequence. A plain
 * class publishing one immutable [State] through a [StateFlow]
 * (the repository/ViewModel convention); every collaborator is
 * injected as a suspend closure so the machine is unit-testable and
 * the module stays dependency-free. Ports the redesigned
 * `OnboardingFlow` from onym-ios (PR #259), including its
 * review-hardened rules: no advance-bypass around an outcome-gated
 * step, fail-closed unresolved probes, complete() only from Done,
 * flow-held services/backup state that survives navigation.
 *
 * Presentation contract: RootScreen shows [OnboardingScreen]
 * full-screen with system-back blocked — the only exits are
 * [complete] on the Done step and the recovery step's "Remind me
 * later".
 *
 * Partial progress is deliberately NOT persisted: the flow lives in
 * memory, and an app killed mid-onboarding starts over at Welcome on
 * next launch. Individual consents the step content applied before
 * the kill are persisted by their own stores (consent pins, seat
 * selections) — only the walk position restarts.
 */
class OnboardingFlow(
    private val store: OnboardingStore,
    /**
     * RESERVED moderation slot. False (the Android default) removes
     * [OnboardingStep.Moderation] from the sequence entirely. True
     * restores iOS step parity for when the moderation seat lands on
     * Android.
     */
    moderationEnabled: Boolean = false,
    /**
     * Whether the moderation authority directory has entries.
     * Consulted once by [start], and only when [moderationEnabled] —
     * the reserved step is the probe's only consumer.
     */
    private val moderationDirectoryNonEmpty: suspend () -> Boolean = { true },
) {

    /** One immutable snapshot of the walk. */
    data class State(
        val step: OnboardingStep = OnboardingStep.Welcome,
        /**
         * Per-step record of what the user decided. Written by
         * [recordOutcome] (step content reporting a consent), [skip]
         * ([StepOutcome.Skipped]), and [advance] (backfills
         * [StepOutcome.NotApplicable] — or [StepOutcome.Unavailable]
         * for moderation over a RESOLVED-empty directory — for steps
         * walked past without a decision). Revisiting a step via
         * [back] keeps the prior outcome until a new one is recorded
         * over it.
         */
        val outcomes: Map<OnboardingStep, StepOutcome> = emptyMap(),
        /**
         * Whether the moderation directory has entries — resolved
         * once by [start]. Tri-state and FAIL-CLOSED: null
         * (unresolved) is treated exactly like "has entries" — the
         * moderation step reads mandatory until the probe answers
         * false. Racing the probe must never open an advance window
         * that a resolved `true` would have closed.
         */
        val moderationDirectoryHasEntries: Boolean? = null,
        /**
         * The services step's path, written by its step content via
         * [recordServicesChoice]. Flow-held (NOT composable state) so
         * Back/forward navigation can't reset the selection chip to a
         * state that contradicts what the user actually configured.
         */
        val servicesChoice: ServicesChoice = ServicesChoice.Recommended,
        /**
         * How far the recovery backup got, written by the recovery
         * step's content via [recordRecoveryBackup] as the backup
         * screen progresses. Never downgrades: [RecoveryBackupState.Verified]
         * stays verified across navigation and re-reveals.
         */
        val recoveryBackupState: RecoveryBackupState = RecoveryBackupState.None,
        /** Set by [complete] after the flag is persisted, so the
         *  presenter can dismiss and hand control back. */
        val completed: Boolean = false,
    ) {
        /** Whether the directory probe has answered — the UI shows a
         *  progress state on the moderation step while this is false. */
        val moderationProbeResolved: Boolean
            get() = moderationDirectoryHasEntries != null
    }

    /** The walk order: all steps, minus the reserved moderation slot
     *  unless enabled. */
    val steps: List<OnboardingStep> =
        if (moderationEnabled) {
            OnboardingStep.entries.toList()
        } else {
            OnboardingStep.entries.filterNot { it == OnboardingStep.Moderation }
        }

    private val moderationInSequence = moderationEnabled

    /**
     * The steps the indicator counts — the user-facing "step N of M".
     * Welcome, the recovery phrase, and Done are unnumbered. With the
     * reserved moderation slot disabled this is exactly
     * [Identity, Services] — "step 1 of 2" / "step 2 of 2".
     */
    private val indicatorSteps: List<OnboardingStep> = buildList {
        add(OnboardingStep.Identity)
        add(OnboardingStep.Services)
        if (moderationEnabled) add(OnboardingStep.Moderation)
    }

    private val _state = MutableStateFlow(
        State(
            // The services outcome is SEEDED as an accepted
            // recommended setup: its screen shows "Selected" on the
            // recommended card from the first frame, so Continue from
            // there IS accepting it — not walking past an unanswered
            // question. Seeding also makes tapping the already-
            // selected card an honest no-op. Sub-surfaces overwrite
            // via [recordOutcome] (a componentId consent recorded by
            // a hub seat wins; all seats share this single outcome,
            // so what survives is whichever seat consented LAST —
            // arbitrary but harmless, the Done summary reads live
            // repository state, not this outcome).
            outcomes = mapOf(
                OnboardingStep.Services to StepOutcome.Consented(null),
            ),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Guards the once-only directory probe kicked by [start]. */
    private val startMutex = Mutex()
    private var started = false

    // ── Step indicator ────────────────────────────────────────────

    /** Zero-based indicator position: "step [index]+1 of [count]". */
    data class Indicator(val index: Int, val count: Int)

    /**
     * The indicator position for a step, or null when the step
     * carries no indicator (welcome, recoveryPhrase, done) — the
     * scaffold must not reserve blank indicator space on those.
     */
    fun indicator(step: OnboardingStep): Indicator? {
        val index = indicatorSteps.indexOf(step)
        if (index == -1) return null
        return Indicator(index = index, count = indicatorSteps.size)
    }

    // ── Skippability & gating ─────────────────────────────────────

    /**
     * Only the recovery-phrase step is skippable ("Remind me later"):
     * - welcome, identity, services — their primary Continue is the
     *   only path forward; the recommended setup IS the default, so a
     *   separate Skip would be redundant;
     * - moderation — NEVER skippable, in any directory state.
     *   Moderation-authority selection is not optional: with entries
     *   present the user must pick and sign, and with an EMPTY
     *   directory there is nothing to decline — the step turns
     *   Continue-only informational instead ([isMandatory] answers
     *   false and [advance] records [StepOutcome.Unavailable]),
     *   which is an acknowledgment, not a skip;
     * - done, which is terminal — its primary action is [complete].
     */
    fun isSkippable(step: OnboardingStep): Boolean =
        step == OnboardingStep.RecoveryPhrase

    /**
     * Whether the moderation obligation is in force at [step]. True
     * exactly for [OnboardingStep.Moderation] while the directory has
     * entries or the probe hasn't answered yet (fail-closed). Only a
     * RESOLVED-empty directory relaxes this — there is no authority
     * to pick, so hard-blocking would brick onboarding; the step
     * becomes Continue-only informational and [advance] records the
     * distinct [StepOutcome.Unavailable] outcome. With
     * `moderationEnabled = false` the step isn't in the sequence and
     * this is never consulted for a live step.
     */
    fun isMandatory(step: OnboardingStep): Boolean =
        isMandatory(step, _state.value)

    private fun isMandatory(step: OnboardingStep, state: State): Boolean {
        if (step != OnboardingStep.Moderation) return false
        return state.moderationDirectoryHasEntries != false
    }

    /**
     * A step whose primary refuses to advance without a recorded
     * outcome (the screen disables the button; [advance] carries the
     * same guard as the second layer):
     * - [OnboardingStep.Identity] always — its content records
     *   [StepOutcome.NotApplicable] only once the identity bootstrap
     *   actually produced a snapshot; a failed bootstrap must not
     *   walk the user on to services and a recovery step whose reveal
     *   cannot work (the fail-closed dead end, with its error card's
     *   "Try again", is intentional);
     * - [OnboardingStep.Moderation] while mandatory (see
     *   [isMandatory]) — dormant behind the reserved slot today;
     * - [OnboardingStep.RecoveryPhrase] always — its primary reads
     *   "I've written it down", which must not be tappable before the
     *   phrase was ever revealed. The step content records the
     *   outcome on reveal; "Remind me later" (skip) is the honest
     *   escape.
     */
    fun requiresOutcomeToAdvance(step: OnboardingStep): Boolean =
        requiresOutcomeToAdvance(step, _state.value)

    /** Snapshot-taking overload: [advance] runs inside a
     *  `_state.update {}` CAS loop, and evaluating the guard against
     *  a fresh `_state.value` read there would mix two snapshots on
     *  a contended retry — the guard must be answered from the SAME
     *  `current` the update is transforming. */
    private fun requiresOutcomeToAdvance(step: OnboardingStep, state: State): Boolean =
        when (step) {
            OnboardingStep.Identity -> true
            OnboardingStep.Moderation -> isMandatory(step, state)
            OnboardingStep.RecoveryPhrase -> true
            else -> false
        }

    /**
     * Whether the recorded outcome at [step] satisfies an
     * outcome-gated step ([requiresOutcomeToAdvance]). A missing
     * outcome never does — and neither does [StepOutcome.Skipped]:
     * skipping records a decision to DEFER, not the thing the gate
     * exists to prove (the identity snapshot, the recovery reveal,
     * the moderation consent). Without this, RecoveryPhrase →
     * "Remind me later" → Back would leave "I've written it down"
     * enabled off the surviving Skipped outcome, with the phrase
     * never revealed. Used by both [advance]'s guard and the
     * screen's primary-disabled logic; the honest exits after
     * skip+back remain the reveal (records Consented) or skipping
     * again.
     */
    fun outcomeSatisfiesGate(step: OnboardingStep): Boolean =
        satisfiesGate(_state.value.outcomes[step])

    private fun satisfiesGate(outcome: StepOutcome?): Boolean =
        outcome != null && outcome !is StepOutcome.Skipped

    // ── Lifecycle ─────────────────────────────────────────────────

    /**
     * Resolve the moderation-directory probe. Idempotent — later
     * calls (Compose recomposition re-entering the LaunchedEffect)
     * never re-run the probe. A no-op when the moderation step is not
     * in the sequence: the probe has no other consumer.
     */
    suspend fun start() {
        if (!moderationInSequence) return
        startMutex.withLock {
            if (started) return
            started = true
        }
        val nonEmpty = moderationDirectoryNonEmpty()
        _state.update { it.copy(moderationDirectoryHasEntries = nonEmpty) }
    }

    /**
     * Step content reports what the user decided at the current step.
     * Recording again overwrites (the user changed their mind before
     * advancing, or came [back] and redid the step).
     */
    fun recordOutcome(outcome: StepOutcome) {
        _state.update { it.copy(outcomes = it.outcomes + (it.step to outcome)) }
    }

    /** The services step's content reports which path the user is on.
     *  Flow-held so the chip survives Back/forward navigation. */
    fun recordServicesChoice(choice: ServicesChoice) {
        _state.update { it.copy(servicesChoice = choice) }
    }

    /**
     * The recovery step's content reports backup progress. Never
     * downgrades: a verified backup stays verified across re-reveals
     * and navigation (ordinal comparison over
     * [RecoveryBackupState]'s declaration order).
     */
    fun recordRecoveryBackup(state: RecoveryBackupState) {
        _state.update { current ->
            if (state.ordinal <= current.recoveryBackupState.ordinal) return@update current
            current.copy(recoveryBackupState = state)
        }
    }

    /**
     * Move to the next step. A step walked past without any recorded
     * outcome is backfilled — [StepOutcome.NotApplicable] for the
     * informational steps, except moderation over a RESOLVED-empty
     * directory, whose Continue-only acknowledgment records the
     * distinct [StepOutcome.Unavailable] (moderation wasn't offered;
     * the user did not opt out). No-op on Done ([complete] is the
     * terminal action), and a no-op on a step that
     * [requiresOutcomeToAdvance] and has none — the primary button
     * must not be a back door around the must-bootstrap identity
     * rule, the must-consent moderation rule, or the must-reveal
     * recovery rule (the screen also disables it; this guard is the
     * second layer).
     */
    fun advance() {
        _state.update { current ->
            val next = neighbor(current.step, offset = 1) ?: return@update current
            if (requiresOutcomeToAdvance(current.step, current) &&
                !satisfiesGate(current.outcomes[current.step])
            ) {
                return@update current
            }
            val outcomes =
                if (current.outcomes[current.step] == null) {
                    val backfill =
                        if (current.step == OnboardingStep.Moderation &&
                            current.moderationDirectoryHasEntries == false
                        ) {
                            StepOutcome.Unavailable
                        } else {
                            StepOutcome.NotApplicable
                        }
                    current.outcomes + (current.step to backfill)
                } else {
                    current.outcomes
                }
            current.copy(step = next, outcomes = outcomes)
        }
    }

    /**
     * Skip the current step, recording [StepOutcome.Skipped]. Guarded
     * by [isSkippable] — a no-op on every step except recoveryPhrase.
     */
    fun skip() {
        _state.update { current ->
            val next = neighbor(current.step, offset = 1) ?: return@update current
            if (!isSkippable(current.step)) return@update current
            current.copy(
                step = next,
                outcomes = current.outcomes + (current.step to StepOutcome.Skipped),
            )
        }
    }

    /**
     * Move to the previous step. No-op on Welcome. The revisited
     * step's prior outcome is kept — [recordOutcome] / [skip]
     * overwrite it if the user decides differently this time.
     */
    fun back() {
        _state.update { current ->
            val previous = neighbor(current.step, offset = -1) ?: return@update current
            current.copy(step = previous)
        }
    }

    /**
     * "Start messaging" on the Done step: persist the completion
     * flag, then publish `completed = true` so the presenter can
     * dismiss. Guarded to the Done step so no programming error can
     * mark onboarding complete mid-sequence.
     */
    suspend fun complete() {
        if (_state.value.step != OnboardingStep.Done) return
        store.markCompleted()
        _state.update { current ->
            val outcomes =
                if (current.outcomes[current.step] == null) {
                    current.outcomes + (current.step to StepOutcome.NotApplicable)
                } else {
                    current.outcomes
                }
            current.copy(outcomes = outcomes, completed = true)
        }
    }

    // ── Private ───────────────────────────────────────────────────

    private fun neighbor(step: OnboardingStep, offset: Int): OnboardingStep? {
        val index = steps.indexOf(step)
        if (index == -1) return null
        return steps.getOrNull(index + offset)
    }
}
