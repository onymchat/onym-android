package app.onym.android.group

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
 * — one shared instance lives in [app.onym.android.AppDependencies].
 * The chats list reads `pending` for the per-group signal, and each
 * group's chat thread renders its own slice of the list as in-thread
 * rows that dispatch Accept / Decline. (Both surfaces used to be one
 * modal + toolbar badge; the requests moved into the thread because
 * nobody found the badge.)
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

    /** Why each request's last Approve failed, keyed by request id.
     *
     *  A map rather than a single slot because the thread renders one
     *  row per pending request: with two outstanding failures a single
     *  slot could only ever explain the newer one, and the older row
     *  would silently lose its explanation while its request was still
     *  there to retry. Each row reads its own entry, so an unrelated
     *  failure can neither overwrite nor leak onto it. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

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
                // Drop errors for requests that are no longer pending.
                // An entry is otherwise only cleared by acting on that
                // same request again, so one removed by the retention
                // sweep — or approved from another device — would leave
                // its failure behind for the process lifetime.
                val live = snapshot.mapTo(HashSet()) { it.id }
                _errors.value = _errors.value.filterKeys { it in live }
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
     * error ordering.
     */
    fun approve(requestId: String) {
        if (_inFlight.value.contains(requestId)) return
        _inFlight.value = _inFlight.value + requestId
        // Clear this request's failure as the retry starts, not when it
        // succeeds. Waiting for `Sent` rendered the spinner and the
        // previous error together — "Anchoring on chain…" under "that
        // didn't work" — which reads as a fresh failure rather than a
        // stale one.
        clearError(requestId)
        viewModelScope.launch {
            val outcome = approver.approve(requestId)
            _inFlight.value = _inFlight.value - requestId
            when (outcome) {
                // No success banner: the confirmation is now the
                // "X joined" notice the approve path writes into the
                // thread, right where the request row was.
                is JoinRequestApprover.ApproveOutcome.Sent -> Unit
                else -> failureReason(outcome)?.let { reason ->
                    _errors.value = _errors.value + (requestId to reason)
                }
            }
        }
    }

    /** This request's last failure, if it has one. Each row reads its
     *  own; nothing else can surface on it. */
    fun error(requestId: String): String? = _errors.value[requestId]

    fun decline(requestId: String) {
        if (_inFlight.value.contains(requestId)) return
        _inFlight.value = _inFlight.value + requestId
        // Same reasoning as `approve`: the error goes when the action
        // starts, so the row never shows a spinner over a stale failure.
        clearError(requestId)
        viewModelScope.launch {
            approver.decline(requestId)
            _inFlight.value = _inFlight.value - requestId
        }
    }

    /** Drop one request's error. Keyed, so acting on one request cannot
     *  clear the explanation another row is still showing.
     *
     *  There is deliberately no dismiss affordance now that the modal is
     *  gone: the error sits on the row it belongs to, and the row is the
     *  thing the founder is going to touch next. */
    private fun clearError(requestId: String) {
        _errors.value = _errors.value - requestId
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
                "No Founder contract selected for this network. Pick one in Settings → Network → Anchors."
            is JoinRequestApprover.ApproveOutcome.NotAdminOfThisGroup ->
                "The active identity isn’t this group’s admin. Switch to the identity that created the group, then try again."
            is JoinRequestApprover.ApproveOutcome.ProofFailed ->
                "Couldn’t generate proof: ${outcome.reason}"
            is JoinRequestApprover.ApproveOutcome.AnchorRejected ->
                "Chain rejected the proof: ${outcome.reason}"
        }
    }
}
