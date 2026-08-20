package app.onym.android.backup.ui

import app.onym.android.backup.BackupStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** What the Settings → Device Backup row/screen shows. Precedence on
 *  [DeviceBackupSettingsFlow.refresh] (highest wins): an unreadable
 *  state file; no accepted terms (never enrolled); an operator
 *  mismatch; an awaiting-payment record; a pending (unresolved)
 *  operation — this outranks Idle/Stale, since unresolved work is
 *  more important to surface than "last known good" — and only then
 *  the ordinary stale-vs-idle read of the last success. */
sealed class DeviceBackupStatus {
    data object Off : DeviceBackupStatus()
    data class Idle(val lastSuccessAt: Instant?) : DeviceBackupStatus()
    data object Running : DeviceBackupStatus()
    data class Stale(val lastSuccessAt: Instant?) : DeviceBackupStatus()
    data class PaymentRequired(val offerIds: List<String>) : DeviceBackupStatus()
    data object TermsChanged : DeviceBackupStatus()
    data object OperatorChanged : DeviceBackupStatus()
    data object CheckingEarlierBackup : DeviceBackupStatus()
    data class Failed(val message: String) : DeviceBackupStatus()
}

/**
 * Drives the Settings → Device Backup surface's status. Re-resolved
 * every time Settings opens (the composition root calls [refresh] on
 * each entry, not just at cold app launch) — see the implementation
 * plan's review-fix checklist for why that matters.
 *
 * Mirrors `DeviceBackupSettingsFlow` in onym-ios OnymBackupUI.
 */
class DeviceBackupSettingsFlow(
    private val stateStore: BackupStateStore,
    private val schedule: BackupSchedule,
    /** The currently-consented operator's componentId, or `null` if
     *  backup isn't consented right now — read fresh, not cached, so a
     *  mid-session operator change is detected. */
    private val currentComponentId: suspend () -> String?,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<DeviceBackupStatus>(DeviceBackupStatus.Off)
    val status: StateFlow<DeviceBackupStatus> = _status.asStateFlow()

    fun refresh() {
        scope.launch {
            _status.value = computeStatus()
        }
    }

    /** Call while a `backUp()`/`retry()` run is in flight. */
    fun markRunning() {
        _status.value = DeviceBackupStatus.Running
    }

    /** The composition root calls this when a run throws
     *  `BackupError.TermsChanged` — a transient signal until the next
     *  [refresh]. Nothing is persisted here; the run was refused at
     *  the operator, which answered `terms_changed` for this upload. */
    fun reportTermsChanged() {
        _status.value = DeviceBackupStatus.TermsChanged
    }

    /** The composition root calls this when a run throws
     *  `BackupError.OperatorChanged` — a transient signal until the
     *  next [refresh], which will independently reach the same
     *  conclusion by comparing [currentComponentId] against the
     *  pinned state. */
    fun reportOperatorChanged() {
        _status.value = DeviceBackupStatus.OperatorChanged
    }

    fun reportFailed(message: String) {
        _status.value = DeviceBackupStatus.Failed(message)
    }

    private suspend fun computeStatus(): DeviceBackupStatus {
        val state = try {
            stateStore.load()
        } catch (e: Exception) {
            return DeviceBackupStatus.Failed(e.message ?: "backup state is unreadable")
        }

        if (state?.acceptedTermsId == null) return DeviceBackupStatus.Off

        val liveComponentId = currentComponentId()
        if (liveComponentId != null && state.componentId != null && liveComponentId != state.componentId) {
            return DeviceBackupStatus.OperatorChanged
        }

        state.pendingPayment?.let { return DeviceBackupStatus.PaymentRequired(it.offerIds) }
        if (state.pendingOperation != null) return DeviceBackupStatus.CheckingEarlierBackup

        val lastSuccess = state.lastSuccessAtEpochSeconds?.let(Instant::ofEpochSecond)
        return if (schedule.isStale(lastSuccess)) {
            DeviceBackupStatus.Stale(lastSuccess)
        } else {
            DeviceBackupStatus.Idle(lastSuccess)
        }
    }
}
