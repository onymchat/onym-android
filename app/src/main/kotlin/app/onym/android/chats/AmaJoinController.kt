package app.onym.android.chats

import app.onym.android.group.IntroCapability
import app.onym.android.inbox.PendingChatsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the empty chats list shows while it resolves itself into the AMA
 * group's join. [Idle] is the ordinary list; [Resolving] is the spinner
 * in place of the pitch; [Confirm] is the confirmation sheet, which the
 * person still sends themselves; [Failed] falls back to the pitch plus
 * a manual entry; [HandedOff] means navigation already happened
 * (already in the group, or the wait is on screen).
 */
sealed interface AmaJoinPhase {
    data object Idle : AmaJoinPhase
    data object Resolving : AmaJoinPhase
    data class Confirm(
        val confirmation: PendingChatsViewModel.JoinConfirmation,
    ) : AmaJoinPhase
    /** [reason] is null when the invite itself no longer decodes. */
    data class Failed(val reason: PendingChatsViewModel.JoinOutcome.FailureReason?) : AmaJoinPhase
    data object HandedOff : AmaJoinPhase
}

/**
 * Remembers that an identity has already been offered the AMA group, so
 * a declined request, a dismissal or a failure is not asked again on
 * every launch. Keyed per identity: a second identity on the same device
 * has its own first launch.
 */
interface AmaJoinAttempts {
    suspend fun hasAttempted(identityKey: String): Boolean
    suspend fun markAttempted(identityKey: String)
}

/**
 * The empty chats list's own way out: one automatic attempt per
 * identity to open the Onym AMA group, through the same
 * [PendingChatsViewModel.prepareJoin] a scanned code uses — so the
 * person lands on the group's rules, not inside it, and the request is
 * still theirs to send.
 *
 * The attempt is idempotent and single-flight: the flag is written
 * before the work starts, so a process death mid-resolve cannot leave a
 * loop, and a second resolve while one is in flight is a no-op.
 */
class AmaJoinController(
    private val attempts: AmaJoinAttempts,
    private val invite: () -> IntroCapability?,
    private val prepareJoin: suspend (IntroCapability) -> PendingChatsViewModel.JoinDestination,
    private val onOpenChat: (groupIdHex: String) -> Unit,
    private val onOpenPending: (rowId: String) -> Unit,
) {

    private val _phase = MutableStateFlow<AmaJoinPhase>(AmaJoinPhase.Idle)
    val phase: StateFlow<AmaJoinPhase> = _phase.asStateFlow()

    private val resolveMutex = Mutex()

    /** The launch path: at most once per identity, ever. */
    suspend fun resolveOnce(identityKey: String) {
        if (attempts.hasAttempted(identityKey)) return
        open(identityKey, remember = true)
    }

    /** The person tapped the entry themselves — the flag does not
     *  apply, because this is not the app deciding on their behalf. */
    suspend fun openManually(identityKey: String) {
        open(identityKey, remember = false)
    }

    /** The confirmation was closed without sending: back to the pitch,
     *  no navigation, no second attempt on this launch. */
    fun onConfirmationDismissed() {
        if (_phase.value is AmaJoinPhase.Confirm) _phase.value = AmaJoinPhase.Idle
    }

    private suspend fun open(identityKey: String, remember: Boolean) {
        resolveMutex.withLock {
            if (_phase.value is AmaJoinPhase.Resolving) return
            // Written before the work: an attempt that dies halfway
            // must not be repeated behind the person's back.
            if (remember) attempts.markAttempted(identityKey)
            val capability = invite()
            if (capability == null) {
                _phase.value = AmaJoinPhase.Failed(reason = null)
                return
            }
            _phase.value = AmaJoinPhase.Resolving
            when (val destination = prepareJoin(capability)) {
                is PendingChatsViewModel.JoinDestination.AlreadyJoined -> {
                    _phase.value = AmaJoinPhase.HandedOff
                    onOpenChat(destination.groupIdHex)
                }
                is PendingChatsViewModel.JoinDestination.Waiting -> {
                    _phase.value = AmaJoinPhase.HandedOff
                    onOpenPending(destination.rowId)
                }
                is PendingChatsViewModel.JoinDestination.Confirm ->
                    _phase.value = AmaJoinPhase.Confirm(destination.confirmation)
                is PendingChatsViewModel.JoinDestination.Failed ->
                    _phase.value = AmaJoinPhase.Failed(destination.reason)
            }
        }
    }
}
