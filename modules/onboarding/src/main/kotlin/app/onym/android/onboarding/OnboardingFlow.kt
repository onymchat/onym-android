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
 * [Moderation] is RESERVED: the moderation seat has no Android
 * implementation yet, so the step only enters the sequence when
 * [OnboardingFlow] is constructed with `moderationEnabled = true`
 * (future parity with onym-ios, whose flow walks all seven steps).
 */
enum class OnboardingStep(val tag: String) {
    Welcome("welcome"),
    DiscoveryConfirm("discoveryConfirm"),
    MessageTransport("messageTransport"),
    BlobTransport("blobTransport"),
    Notary("notary"),
    Moderation("moderation"),
    Done("done"),
}

/**
 * What happened at one step, modeled abstractly — the flow never
 * links against the modules whose surfaces produce these outcomes
 * (:discovery's module consent). The app-layer step content (PR 3)
 * reports back through this type.
 */
sealed interface StepOutcome {
    /**
     * The user consented at this step. [componentId] names the chosen
     * component when the step's surface knows one (catalog picks);
     * null for consents without a component identity (e.g. confirming
     * the seeded discovery source).
     */
    data class Consented(val componentId: String?) : StepOutcome

    /** The user skipped the step; the seat falls back to today's
     *  defaults. */
    data object Skipped : StepOutcome

    /** The step had nothing to decide (informational steps: welcome,
     *  done, moderation-with-empty-directory). */
    data object NotApplicable : StepOutcome
}

/**
 * State machine for the first-launch onboarding sequence. A plain
 * class publishing one immutable [State] through a [StateFlow]
 * (the repository/ViewModel convention — see `DiscoveryRepository`,
 * `ModuleConsentViewModel`); every collaborator is injected as a
 * suspend closure so the machine is unit-testable and the module
 * stays dependency-free. Ports `OnboardingFlow` from onym-ios
 * (PR #249), including its review-hardened rules: no advance-bypass
 * around a mandatory step, fail-closed unresolved probes, complete()
 * only from Done.
 *
 * Presentation contract (PR 3): RootScreen shows [OnboardingScreen]
 * full-screen with system-back blocked — the only exits are
 * [complete] on the Done step and the per-step Skip affordances.
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
     * [OnboardingStep.Moderation] from the sequence entirely — with
     * it absent there are no mandatory steps and every middle step is
     * skippable. True restores iOS step parity for when the
     * moderation seat lands on Android.
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
         * [StepOutcome.NotApplicable] for steps walked past without a
         * decision). Revisiting a step via [back] keeps the prior
         * outcome until a new one is recorded over it.
         */
        val outcomes: Map<OnboardingStep, StepOutcome> = emptyMap(),
        /**
         * Whether the moderation directory has entries — resolved
         * once by [start]. Tri-state and FAIL-CLOSED: null
         * (unresolved) is treated exactly like "has entries" — the
         * moderation step reads unskippable/mandatory until the probe
         * answers false. Racing the probe must never open a skip
         * window that a resolved `true` would have closed.
         */
        val moderationDirectoryHasEntries: Boolean? = null,
        /** Set by [complete] after the flag is persisted, so the
         *  presenter can dismiss and hand control back. */
        val completed: Boolean = false,
    ) {
        /** Whether the directory probe has answered — the UI shows a
         *  progress state on the skip affordance while this is false
         *  on the moderation step. */
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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Guards the once-only directory probe kicked by [start]. */
    private val startMutex = Mutex()
    private var started = false

    // ── Step indicator ────────────────────────────────────────────

    /** Zero-based position of the current step, for the indicator. */
    val stepIndex: Int
        get() = steps.indexOf(_state.value.step).coerceAtLeast(0)

    /** Total number of steps in this walk, for the indicator. */
    val stepCount: Int
        get() = steps.size

    // ── Skippability ──────────────────────────────────────────────

    /**
     * Every step is skippable except:
     * - [OnboardingStep.Welcome] — its primary Continue is the only
     *   path forward; a separate Skip would be redundant (and the
     *   screen never renders one);
     * - [OnboardingStep.Moderation] unless the directory probe has
     *   ANSWERED that the authority directory is empty — with entries
     *   present the moderation gate would block right after
     *   onboarding anyway, so letting the user skip here would be a
     *   lie, and while the probe is still unresolved we fail closed
     *   rather than open a racy skip window;
     * - [OnboardingStep.Done], which is terminal — there is nothing
     *   after it to skip to (its primary action is [complete]).
     */
    fun isSkippable(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.Welcome -> false
        OnboardingStep.Moderation -> _state.value.moderationDirectoryHasEntries == false
        OnboardingStep.Done -> false
        else -> true
    }

    /**
     * A mandatory step cannot be walked past without a recorded
     * outcome — [advance] refuses until the step content reports one.
     * The gating is generic (any step this returns true for is
     * primary-button-blocked), but today it holds exactly for
     * [OnboardingStep.Moderation] while the directory has entries or
     * the probe hasn't answered yet (fail-closed, the complement of
     * [isSkippable]). With `moderationEnabled = false` no step in the
     * sequence is ever mandatory.
     */
    fun isMandatory(step: OnboardingStep): Boolean {
        if (step != OnboardingStep.Moderation) return false
        return _state.value.moderationDirectoryHasEntries != false
    }

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

    /**
     * Move to the next step. A step walked past without any recorded
     * outcome is backfilled as [StepOutcome.NotApplicable] — the
     * informational steps (welcome; moderation with an empty
     * directory) advance through here without ever recording. No-op
     * on Done ([complete] is the terminal action), and a no-op on a
     * mandatory step with no recorded outcome — the primary button
     * must not be a back door around the unskippable-moderation rule
     * (the screen also disables it; this guard is the second layer).
     */
    fun advance() {
        _state.update { current ->
            val next = neighbor(current.step, offset = 1) ?: return@update current
            if (isMandatory(current.step) && current.outcomes[current.step] == null) {
                return@update current
            }
            val outcomes =
                if (current.outcomes[current.step] == null) {
                    current.outcomes + (current.step to StepOutcome.NotApplicable)
                } else {
                    current.outcomes
                }
            current.copy(step = next, outcomes = outcomes)
        }
    }

    /**
     * Skip the current step, recording [StepOutcome.Skipped]. Guarded
     * by [isSkippable] — a no-op on an unskippable step (welcome,
     * moderation with a non-empty directory, done).
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
     * "Start" on the Done step: persist the completion flag, then
     * publish `completed = true` so the presenter can dismiss.
     * Guarded to the Done step so no programming error can mark
     * onboarding complete mid-sequence.
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
