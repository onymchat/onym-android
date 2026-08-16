package app.onym.android.moderation.ui

import app.onym.android.moderation.BanState
import app.onym.android.moderation.CaseNotice
import app.onym.android.moderation.CheckRequiredReason
import app.onym.android.moderation.GateCheckRepository
import app.onym.android.moderation.GateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What RootScreen switches on. Only [Banned], [CheckRequired], and
 * [NeedsConsent] replace the shell. */
sealed interface RootGate {
    /** Operating; non-empty [openCases] is a (deferred-UI) banner
     * seam, never a block — the case-open mark must not degrade
     * service. */
    data class Operational(val openCases: List<CaseNotice> = emptyList()) : RootGate

    /** No active mandate and at least one authority to consent to:
     * the full-screen consent surface (post-onboarding path — the
     * onboarding step covers first launch). */
    data object NeedsConsent : RootGate

    data class Banned(val state: BanState) : RootGate

    data class CheckRequired(val reason: CheckRequiredReason) : RootGate
}

/**
 * Folds the repositories' snapshots into the one [RootGate], porting
 * iOS's `ModerationGateFlow.recompute` with its two deliberate
 * softenings:
 *
 * - **no mandate + an empty authority directory → operational.** The
 *   protocol remains usable without a mandate; it is this interface
 *   that requires one, and it cannot require what it cannot offer.
 * - **while the first check is still in flight the app renders** —
 *   `NotMandated`/unknown never blocks launch on the network.
 *
 * `ENROLLMENT_LOST` routes to [RootGate.NeedsConsent] (re-consent
 * re-enrolls) when authorities are available, else stays a blocking
 * [RootGate.CheckRequired].
 */
class ModerationGateFlow(
    private val gate: GateCheckRepository,
    private val authoritiesAvailable: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<RootGate>(RootGate.Operational())
    val snapshots: StateFlow<RootGate> = state.asStateFlow()

    /**
     * Last directory answer. Never trusted while stale in the
     * blocking direction: any status whose RootGate depends on it
     * ([GateStatus.NotMandated], `ENROLLMENT_LOST`) re-probes before
     * recomputing when the cached answer is `false` — a launch-time
     * network blip must not pin re-consent unreachable for the
     * process lifetime. Sampling failure still means "not available",
     * which softens toward operational for the unmandated and keeps
     * `ENROLLMENT_LOST` a retryable block for the mandated.
     */
    @Volatile
    private var directoryNonEmpty = false

    /**
     * The user declined to wait out an unreachable authority at the
     * consent surface ("Continue"). Process-lifetime only — the next
     * launch asks again — and scoped to [GateStatus.NotMandated]: a
     * mandated user whose enrollment the backend lost cannot defer
     * their way past the gate.
     */
    @Volatile
    private var consentDeferred = false

    private var collectJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            gate.snapshots.collect { status ->
                if (dependsOnDirectory(status) && !directoryNonEmpty) probeDirectory()
                recomputeFrom(status)
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    /** Re-sample the directory (retry buttons, post-onboarding source
     * installs) and recompute. */
    suspend fun refreshDirectory() {
        probeDirectory()
        recomputeFrom(gate.snapshots.value)
    }

    /** Soften an unreachable-authority consent surface into the app,
     * for this process only. */
    fun deferConsent() {
        consentDeferred = true
        recomputeFrom(gate.snapshots.value)
    }

    private suspend fun probeDirectory() {
        directoryNonEmpty = runCatching { authoritiesAvailable() }.getOrDefault(false)
    }

    private fun dependsOnDirectory(status: GateStatus): Boolean = when (status) {
        GateStatus.NotMandated -> true
        is GateStatus.GateCheckRequired -> status.reason == CheckRequiredReason.ENROLLMENT_LOST
        else -> false
    }

    private fun recomputeFrom(status: GateStatus) {
        state.value = when (status) {
            GateStatus.NotMandated -> when {
                consentDeferred -> RootGate.Operational()
                directoryNonEmpty -> RootGate.NeedsConsent
                else -> RootGate.Operational()
            }
            is GateStatus.Operational -> RootGate.Operational(status.openCases)
            is GateStatus.Banned -> RootGate.Banned(status.state)
            is GateStatus.GateCheckRequired -> when (status.reason) {
                CheckRequiredReason.ENROLLMENT_LOST ->
                    if (directoryNonEmpty) {
                        RootGate.NeedsConsent
                    } else {
                        RootGate.CheckRequired(status.reason)
                    }
                else -> RootGate.CheckRequired(status.reason)
            }
        }
    }

    /** Post-consent invariant (iOS `consentCompleted`): a fresh check
     * immediately, so the gate reflects the new mandate. */
    fun consentCompleted() {
        consentDeferred = false
        scope.launch { gate.checkNow() }
    }

    fun appForegrounded() {
        scope.launch { gate.checkNow() }
    }

    /**
     * Retry re-probes the directory before re-checking: for
     * `ENROLLMENT_LOST` the directory answer IS what the retry can
     * change (re-consent becomes reachable), and `GateStatus` is a
     * value — an identical re-derivation would not re-emit through
     * the collect, so the refresh cannot ride on it.
     */
    fun tappedRetry() {
        scope.launch {
            refreshDirectory()
            gate.checkNow()
        }
    }
}
