package chat.onym.android.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * View-model for the approver UI. Mirrors `ApproveRequestsFlow.swift`
 * — one shared instance lives in [chat.onym.android.AppDependencies],
 * the toolbar badge on the chats screen watches `pending.size`, and
 * the modal [ApproveRequestsScreen] consumes the full list +
 * dispatches Approve / Decline taps.
 *
 * Purely a thin wrapper over [JoinRequestApprover] — no UI logic
 * beyond mapping outcomes to a user-facing reason string.
 *
 * [start] is idempotent so any composable's `LaunchedEffect` can
 * call it without double-subscribing.
 */
class ApproveRequestsViewModel(
    private val approver: JoinRequestApproving,
) : ViewModel() {

    private val _pending = MutableStateFlow<List<JoinRequestApprover.PendingRequest>>(emptyList())
    val pending: StateFlow<List<JoinRequestApprover.PendingRequest>> = _pending.asStateFlow()

    /** Last failed-approve reason, or null. Cleared on the next
     *  successful Approve / Decline / dismiss. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Brief success banner copy after a `Sent` approval. PR 91 adds
     *  the auto-dismiss timer + content; PR 76 just exposes the
     *  field so the screen can render whatever lands here. */
    private val _lastSuccessMessage = MutableStateFlow<String?>(null)
    val lastSuccessMessage: StateFlow<String?> = _lastSuccessMessage.asStateFlow()

    /** Request IDs whose Approve / Decline call is currently in
     *  flight. PR 90 adds the per-row spinner UI; PR 76 maintains
     *  the state (so debounce works) without rendering it. */
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    private var streamJob: Job? = null

    /** Subscribe to [JoinRequestApprover.pending]. Idempotent — a
     *  second call replaces the prior collector. */
    fun start() {
        if (streamJob != null) return
        approver.start()
        streamJob = viewModelScope.launch {
            approver.pending.collectLatest { snapshot ->
                _pending.value = snapshot
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    fun isInFlight(requestId: String): Boolean = _inFlight.value.contains(requestId)

    /**
     * Approve a pending request. Debounced on [_inFlight] so a
     * rapid double-tap doesn't dispatch twice — entering the proof +
     * chain submission path twice is wasted cycles + can confuse
     * `lastError` ordering.
     */
    fun approve(requestId: String) {
        if (_inFlight.value.contains(requestId)) return
        val joinerAlias = _pending.value.firstOrNull { it.id == requestId }?.joinerDisplayLabel
        _inFlight.value = _inFlight.value + requestId
        viewModelScope.launch {
            val outcome = approver.approve(requestId)
            _inFlight.value = _inFlight.value - requestId
            when (outcome) {
                is JoinRequestApprover.ApproveOutcome.Sent -> {
                    _lastError.value = null
                    val trimmed = joinerAlias?.trim().orEmpty()
                    val label = if (trimmed.isEmpty()) "this person" else trimmed
                    _lastSuccessMessage.value = "$label is now in the group."
                }
                else -> {
                    _lastSuccessMessage.value = null
                    _lastError.value = failureReason(outcome)
                }
            }
        }
    }

    fun decline(requestId: String) {
        if (_inFlight.value.contains(requestId)) return
        _inFlight.value = _inFlight.value + requestId
        viewModelScope.launch {
            approver.decline(requestId)
            _inFlight.value = _inFlight.value - requestId
            _lastError.value = null
        }
    }

    fun dismissError() {
        _lastError.value = null
    }

    private companion object {
        /** Translate an approve [JoinRequestApprover.ApproveOutcome]
         *  into a user-facing reason. PR 76 ships the V1 outcomes
         *  only; later PRs add Tyranny-anchor branches. Keep the
         *  mapping local so later prompts only add cases. */
        fun failureReason(outcome: JoinRequestApprover.ApproveOutcome): String? = when (outcome) {
            is JoinRequestApprover.ApproveOutcome.Sent -> null
            is JoinRequestApprover.ApproveOutcome.UnknownGroup ->
                "This invite isn’t for any group on this device."
            is JoinRequestApprover.ApproveOutcome.UnknownRequest ->
                "Request expired or was already handled."
            is JoinRequestApprover.ApproveOutcome.NoIdentityLoaded ->
                "Sign in first."
            is JoinRequestApprover.ApproveOutcome.TransportFailed ->
                "Couldn’t send: ${outcome.reason}"
            is JoinRequestApprover.ApproveOutcome.OutdatedJoinerClient ->
                "Joiner is on an outdated app. Ask them to update."
            is JoinRequestApprover.ApproveOutcome.NoActiveRelayer ->
                "No chain relayer configured. Set one in Settings → Network → Relayer."
            is JoinRequestApprover.ApproveOutcome.NoContractBinding ->
                "No Tyranny contract selected for this network. Pick one in Settings → Network → Anchors."
            is JoinRequestApprover.ApproveOutcome.NotAdminOfThisGroup ->
                "The active identity isn’t this group’s admin. Switch to the identity that created the group, then try again."
            is JoinRequestApprover.ApproveOutcome.ProofFailed ->
                "Couldn’t generate proof: ${outcome.reason}"
            is JoinRequestApprover.ApproveOutcome.AnchorRejected ->
                "Chain rejected the proof: ${outcome.reason}"
        }
    }
}
