package app.onym.android.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-thread ViewModel for the chat screen. Subscribes to one
 * group's message stream and exposes a `send(body)` action.
 *
 * The chat-message render pipeline (custom bubble cells, auto-scroll,
 * keyboard avoidance) is a future PR's surface — today the screen
 * only renders a placeholder. The VM ships the data plumbing so
 * future work is a UI-only diff.
 *
 * Mirrors `ChatThreadView.swift`'s data dependencies from onym-ios
 * PR #151 (iOS uses a UIKit controller; Android uses Compose with
 * a VM the screen collects).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatThreadViewModel(
    val groupId: String,
    private val groupRepository: GroupRepository,
    private val messageRepository: MessageRepository,
    private val sendMessage: suspend (groupId: String, body: String) -> Unit,
) : ViewModel() {

    /** Latest snapshot of the [ChatGroup] this thread renders.
     *  `null` when no group with [groupId] exists on this device
     *  (deleted, or belongs to a different identity). UI surfaces
     *  a "group not found" empty state in that case. */
    val group: StateFlow<ChatGroup?> = groupRepository.snapshots
        .map { groups -> groups.firstOrNull { it.id == groupId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = groupRepository.snapshots.value
                .firstOrNull { it.id == groupId },
        )

    /** Hot stream of this group's messages, oldest first. Backed by
     *  [MessageRepository.snapshots] — a `SharedFlow`-equivalent
     *  resolves on first collection. */
    val messages: StateFlow<List<ChatMessage>> = MutableStateFlow<List<ChatMessage>>(emptyList())
        .also { initial ->
            viewModelScope.launch {
                messageRepository.snapshots(groupId).collect { initial.value = it }
            }
        }
        .asStateFlow()

    private val _sendInFlight = MutableStateFlow(false)
    /** `true` while a send is queued through the interactor. UI uses
     *  this to gray out the send button and disable the input. */
    val sendInFlight: StateFlow<Boolean> = _sendInFlight.asStateFlow()

    private val _lastSendError = MutableStateFlow<String?>(null)
    /** Last-seen send error, surfaced as a banner / toast. Cleared
     *  on the next successful send or via [clearError]. */
    val lastSendError: StateFlow<String?> = _lastSendError.asStateFlow()

    /**
     * Fire-and-forget send. The interactor handles the optimistic
     * insert + status flip; the UI gets pending → sent / failed
     * transitions through [messages] without any extra wiring here.
     */
    fun send(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _sendInFlight.value = true
            try {
                sendMessage(groupId, trimmed)
                _lastSendError.value = null
            } catch (e: SendMessageError) {
                _lastSendError.value = e.message ?: e.javaClass.simpleName
            } catch (e: Throwable) {
                _lastSendError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _sendInFlight.value = false
            }
        }
    }

    fun clearError() { _lastSendError.value = null }
}
