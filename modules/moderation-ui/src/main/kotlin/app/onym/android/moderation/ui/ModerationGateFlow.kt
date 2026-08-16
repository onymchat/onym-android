package app.onym.android.moderation.ui

import app.onym.android.moderation.BanState
import app.onym.android.moderation.CaseNotice
import app.onym.android.moderation.CheckRequiredReason
import app.onym.android.moderation.GateCheckRepository
import app.onym.android.moderation.GateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    /**
     * The full-screen consent surface (post-onboarding path — the
     * onboarding step covers first launch). [canDefer] is true only
     * for the never-mandated: they may soften an unreachable
     * authority into the app for this process. An enrollment-lost
     * user is *mandated* — the gate refused them — so their consent
     * surface offers no Continue; a dead button is worse than none.
     */
    data class NeedsConsent(val canDefer: Boolean) : RootGate

    data class Banned(val state: BanState) : RootGate

    /** [authorityContact] rides along for
     * `REIDENTIFICATION_REQUIRED`, whose screen has no retry and no
     * ban notice to point at — without a contact it would be the
     * silent brick the profile forbids. */
    data class CheckRequired(
        val reason: CheckRequiredReason,
        val authorityContact: String? = null,
    ) : RootGate
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
    /** The active mandate's human contact line (see
     * `MandateRecord.authorityContactLine`), for the
     * reidentification screen. Null when nothing is on file. */
    private val reidentificationContact: suspend () -> String? = { null },
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
            // collectLatest + recompute-first: a status is applied with
            // the cached answers immediately, THEN refined once the
            // probes land — and a newer status (a `Banned` arriving
            // behind a `NotMandated`) cancels a hung probe instead of
            // queueing behind its network timeout.
            gate.snapshots.collectLatest { status ->
                recomputeFrom(status)
                var refined = false
                if (dependsOnDirectory(status) && !directoryNonEmpty) {
                    probeDirectory()
                    refined = true
                }
                if (needsContact(status) && contactLine == null) {
                    contactLine = swallowingFailures { reidentificationContact() }
                    refined = true
                }
                if (refined) recomputeFrom(status)
            }
        }
    }

    /** Cached contact for the reidentification screen; re-read only
     * while absent (the active mandate cannot change underneath a
     * blocked gate). */
    @Volatile
    private var contactLine: String? = null

    private fun needsContact(status: GateStatus): Boolean =
        status is GateStatus.GateCheckRequired &&
            status.reason == CheckRequiredReason.REIDENTIFICATION_REQUIRED

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
        directoryNonEmpty = swallowingFailures { authoritiesAvailable() } ?: false
    }

    /**
     * Failure tolerance that stays CANCELLATION-TRANSPARENT. A bare
     * `runCatching` here swallowed the CancellationException
     * `collectLatest` uses to retire a block when a newer status
     * lands — the "cancelled" block then ran to completion as a
     * zombie and its final recompute stomped the newer status with a
     * stale one (observed as the consent surface never appearing when
     * `ENROLLMENT_LOST` arrived while `NotMandated`'s probe was
     * in flight).
     */
    private suspend fun <T> swallowingFailures(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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
                directoryNonEmpty -> RootGate.NeedsConsent(canDefer = true)
                else -> RootGate.Operational()
            }
            is GateStatus.Operational -> RootGate.Operational(status.openCases)
            is GateStatus.Banned -> RootGate.Banned(status.state)
            is GateStatus.GateCheckRequired -> when (status.reason) {
                CheckRequiredReason.ENROLLMENT_LOST ->
                    if (directoryNonEmpty) {
                        // Mandated but unenrolled: re-consent is the
                        // route, deferring past the gate is not.
                        RootGate.NeedsConsent(canDefer = false)
                    } else {
                        RootGate.CheckRequired(status.reason)
                    }
                CheckRequiredReason.REIDENTIFICATION_REQUIRED ->
                    RootGate.CheckRequired(status.reason, authorityContact = contactLine)
                else -> RootGate.CheckRequired(status.reason)
            }
        }
    }

    /** Post-consent invariant (iOS `consentCompleted`): a fresh check
     * immediately, so the gate reflects the new mandate. Forced past
     * the coalesced flight — a check already on the wire read the
     * pre-consent record, and joining it would land `no_mandate` and
     * bounce the user back to the consent surface they just
     * completed. */
    fun consentCompleted() {
        consentDeferred = false
        scope.launch { gate.checkNow(force = true) }
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
