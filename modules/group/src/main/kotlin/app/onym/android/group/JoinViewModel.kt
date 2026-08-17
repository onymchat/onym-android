package app.onym.android.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the post-deeplink-tap "Join this chat" surface. State machine:
 *
 *  ```
 *           ┌──────► Ready ──── send() ──► Sending
 *  init ────┤                                │
 *           │                                ▼
 *           │                       ┌─►  AwaitingApproval ─┐
 *           │                       │  (sender.send → Sent)│
 *           │                       │                      │
 *           │  Failed ◄─── send() ──┘                      │
 *           │  (sender.send → ...)                         │
 *           │                                              ▼
 *           └──────► Approved ◄─────────────────────────── group lands in repo
 *                  (already a member, OR
 *                   sealed invitation arrives
 *                   via the existing inbox-fanout
 *                   pump after inviter Approves)
 *  ```
 *
 *  - **Ready**: capability decoded, joiner hasn't tapped Send yet.
 *  - **Sending**: `JoinRequestSender.send` in flight. Debounced — a
 *    second `send()` while one is in flight is a no-op.
 *  - **AwaitingApproval**: request shipped, waiting for the inviter
 *    to tap Approve in their app and ship the sealed invitation back.
 *    Persisted in-memory only; backgrounding + returning is fine
 *    while the process lives, but a force-quit drops the wait
 *    (the inviter's Approve still works — the invitation lands in
 *    the joiner's persisted inbox via `IncomingInvitationsRepository`
 *    and surfaces as a chat the next time the joiner opens the app).
 *  - **Approved**: the matching `ChatGroup` has appeared in the
 *    repository — either because the joiner is already a member
 *    (re-tap of an old link) or because the inviter approved + the
 *    invitation pipeline materialized the group.
 *  - **Failed**: surface a reason + Retry. Retry resets to Ready
 *    and re-fires `send`.
 *
 * The repository watcher runs for the VM's lifetime — it can flip to
 * `Approved` from any non-terminal state. This handles the
 * already-a-member case at construction and the sealed-invitation-
 * arrives case after Send.
 */
class JoinViewModel(
    val capability: IntroCapability,
    /** Wait before flipping to [State.Unanswered], null to never flip.
     *  Injected because `runTest`'s virtual clock skips `delay`. */
    private val unansweredAfterMillis: Long? = UNANSWERED_AFTER_MILLIS,
    /** Suspend function that ships a join request and returns the
     *  outcome. Production wires `JoinRequestSender::send` (which
     *  builds the payload, seals to [IntroCapability.introPublicKey],
     *  and ships via Nostr). Tests pass a stub lambda — the VM only
     *  depends on the (capability, label) → outcome contract, not
     *  on the crypto / transport machinery. */
    private val submitRequest: suspend (IntroCapability, String) -> JoinRequestSender.Outcome,
    private val groupRepository: GroupRepository,
    /** Pre-filled into the display-label TextField. Not authoritative
     *  — the user can edit before Send. The factory in
     *  [app.onym.android.OnymApplication] derives it from the
     *  active identity's display name at VM-construction time. */
    val suggestedDisplayLabel: String,
) : ViewModel() {

    sealed class State {
        data class Ready(val capability: IntroCapability) : State()
        object Sending : State()
        object AwaitingApproval : State()
        /** Nothing came back in time. Not a failure — a revoked link
         *  and a slow host are indistinguishable from here. */
        object Unanswered : State()
        data class Approved(val group: ChatGroup) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Ready(capability))
    val state: StateFlow<State> = _state.asStateFlow()

    private var sendJob: Job? = null
    private var unansweredJob: Job? = null

    init {
        // Watch the repository for the matching group throughout the
        // VM's life. Flips to Approved as soon as the group appears,
        // regardless of which non-terminal state we're in.
        viewModelScope.launch {
            groupRepository.snapshots.collect { groups ->
                val match = groups.firstOrNull {
                    it.groupIdBytes.contentEquals(capability.groupId)
                } ?: return@collect
                when (_state.value) {
                    is State.Approved -> Unit  // already terminal
                    else -> {
                        // Beats Unanswered too: a late approval is
                        // still an approval.
                        unansweredJob?.cancel()
                        _state.value = State.Approved(match)
                    }
                }
            }
        }
    }

    /**
     * Ship the join request. No-op if a previous `send` is in flight
     * (debounce — protects against double-tap on the primary button)
     * or if state isn't `Ready` / `Failed`.
     */
    fun send(displayLabel: String) {
        if (sendJob?.isActive == true) return
        val current = _state.value
        if (current !is State.Ready && current !is State.Failed &&
            current !is State.Unanswered
        ) return
        sendJob = viewModelScope.launch {
            _state.value = State.Sending
            val outcome = submitRequest(capability, displayLabel)
            // The repository watcher may have flipped us to Approved
            // while we were awaiting — defer to it if so.
            if (_state.value is State.Approved) return@launch
            _state.value = when (outcome) {
                JoinRequestSender.Outcome.Sent -> {
                    startUnansweredTimer()
                    State.AwaitingApproval
                }
                is JoinRequestSender.Outcome.NoIdentityLoaded ->
                    State.Failed("Sign in first.")
                is JoinRequestSender.Outcome.TransportFailed ->
                    State.Failed("Couldn't send: ${outcome.reason}")
            }
        }
    }

    /**
     * Flip to [State.Unanswered] at the deadline. A late approval
     * still overwrites it via the watcher.
     */
    private fun startUnansweredTimer() {
        val after = unansweredAfterMillis ?: return
        unansweredJob?.cancel()
        unansweredJob = viewModelScope.launch {
            delay(after)
            if (_state.value is State.AwaitingApproval) {
                _state.value = State.Unanswered
            }
        }
    }

    companion object {
        /**
         * Generous: approval is a human tap plus a proof and a chain
         * round-trip, so anything short cries wolf on a thinking host.
         */
        const val UNANSWERED_AFTER_MILLIS: Long = 90_000L
    }
}
