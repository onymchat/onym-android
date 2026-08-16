package app.onym.android.moderation.ui

import app.onym.android.moderation.BanState
import app.onym.android.moderation.CaseNotice
import app.onym.android.moderation.CheckRequiredReason
import app.onym.android.moderation.GateCheckRepository
import app.onym.android.moderation.GateStatus
import app.onym.android.moderation.ModerationRepository
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
    private val moderation: ModerationRepository,
    private val gate: GateCheckRepository,
    private val authoritiesAvailable: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<RootGate>(RootGate.Operational())
    val snapshots: StateFlow<RootGate> = state.asStateFlow()

    /** Sampled at start and on demand; sampling failure means "not
     * available", which softens toward operational, never blocks. */
    @Volatile
    private var directoryNonEmpty = false

    fun start() {
        scope.launch {
            directoryNonEmpty = runCatching { authoritiesAvailable() }.getOrDefault(false)
            recomputeFrom(gate.snapshots.value)
            gate.snapshots.collect { recomputeFrom(it) }
        }
    }

    /** Re-sample the directory (e.g. after onboarding installs
     * sources) and recompute. */
    suspend fun refreshDirectory() {
        directoryNonEmpty = runCatching { authoritiesAvailable() }.getOrDefault(false)
        recomputeFrom(gate.snapshots.value)
    }

    private fun recomputeFrom(status: GateStatus) {
        state.value = when (status) {
            GateStatus.NotMandated ->
                if (directoryNonEmpty) RootGate.NeedsConsent else RootGate.Operational()
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
        scope.launch { gate.checkNow() }
    }

    fun appForegrounded() {
        scope.launch { gate.checkNow() }
    }

    fun tappedRetry() {
        scope.launch { gate.checkNow() }
    }

    /** Exposed for the consent flow's post-consent hook. */
    val moderationRepository: ModerationRepository get() = moderation
}
