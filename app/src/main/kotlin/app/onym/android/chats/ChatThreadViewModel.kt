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
/** A picked media item staged in the composer's preview strip, awaiting
 *  Send. Holds a thumbnail (for the strip) + the raw source (for the
 *  album send). */
data class PendingMediaItem(
    val id: java.util.UUID,
    val thumbnail: android.graphics.Bitmap?,
    val source: ChatMediaSource,
)

class ChatThreadViewModel(
    val groupId: String,
    private val groupRepository: GroupRepository,
    private val messageRepository: MessageRepository,
    private val sendMessage: suspend (
        groupId: String,
        body: String,
        replyToMessageId: java.util.UUID?,
    ) -> Unit,
    /** Send an image message (encode → encrypt → upload → fan-out).
     *  Defaulted to a no-op so tests that don't exercise images keep
     *  their construction sites unchanged. */
    private val sendImage: suspend (groupId: String, imageData: ByteArray) -> Unit = { _, _ -> },
    /** Send a video message (transcode → encrypt → upload → fan-out).
     *  Defaulted to a no-op so tests that don't exercise video keep
     *  their construction sites unchanged. */
    private val sendVideo: suspend (groupId: String, videoUri: android.net.Uri) -> Unit = { _, _ -> },
    /** Send a multi-media album (encode/seal each → upload → fan-out). */
    private val sendAlbum: suspend (groupId: String, sources: List<ChatMediaSource>) -> Unit = { _, _ -> },
    private val retryMessage: suspend (groupId: String, messageId: java.util.UUID) -> Unit = { _, _ -> },
    /** Delete a message locally (the failed-media Delete action). */
    private val deleteMessage: suspend (groupId: String, messageId: java.util.UUID) -> Unit = { _, _ -> },
    /** Loader the UI uses to fetch + decrypt image attachments. */
    val imageLoader: ChatImageLoader? = null,
    /** Loader the UI uses to fetch + decrypt video blobs for playback. */
    val videoLoader: ChatVideoLoader? = null,
    /** Ships read receipts for incoming messages the user is viewing.
     *  Defaulted to a no-op so tests that don't exercise receipts keep
     *  their construction sites unchanged. */
    private val chatReceiptSender: ChatReceiptSending = NoopChatReceiptSender(),
    /** Symmetric read-receipt gate — when `false`, no read receipts are
     *  emitted (and, on the receive side, none are honored). */
    private val readReceiptsEnabled: () -> Boolean = { true },
) : ViewModel() {

    /** Incoming message ids we've already emitted a read receipt for,
     *  so re-emissions of the messages flow while the thread stays open
     *  don't re-send. Touched only from the single messages collector. */
    private val ackedReadIds = mutableSetOf<java.util.UUID>()

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
                messageRepository.snapshots(groupId).collect {
                    initial.value = it
                    // The thread is on-screen while this collector runs,
                    // so any incoming message here is "read". Gated by
                    // the symmetric setting, batched per sender, deduped
                    // via [ackedReadIds].
                    sendReadReceipts(it)
                }
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

    private val _replyingTo = MutableStateFlow<java.util.UUID?>(null)
    /** The message the composer is currently replying to, if any. Set
     *  by a swipe-to-reply on a bubble ([armReply]), cleared on cancel
     *  ([cancelReply]) or after a send. The screen resolves the quoted
     *  sender + snippet from [messages] and shows the composer banner
     *  while this is non-null. */
    val replyingTo: StateFlow<java.util.UUID?> = _replyingTo.asStateFlow()

    /** Arm a reply to [messageId]. The screen reveals the "Replying
     *  to {name}" banner and focuses the composer. */
    fun armReply(messageId: java.util.UUID) { _replyingTo.value = messageId }

    /** Disarm the reply (cancel button, or post-send). */
    fun cancelReply() { _replyingTo.value = null }

    /**
     * Fire-and-forget send. The interactor handles the optimistic
     * insert + status flip; the UI gets pending → sent / failed
     * transitions through [messages] without any extra wiring here.
     *
     * Threads the currently-armed reply target (if any) into the
     * interactor so the sent message renders its quote, then clears
     * the reply immediately so the banner drops on tap.
     */
    fun send(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        val replyTarget = _replyingTo.value
        _replyingTo.value = null
        viewModelScope.launch {
            _sendInFlight.value = true
            try {
                sendMessage(groupId, trimmed, replyTarget)
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

    /** Send an image message with the given raw (undecoded) bytes. */
    fun sendImage(imageData: ByteArray) {
        viewModelScope.launch {
            _sendInFlight.value = true
            try {
                sendImage(groupId, imageData)
                _lastSendError.value = null
            } catch (e: Throwable) {
                _lastSendError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _sendInFlight.value = false
            }
        }
    }

    /** Send a video message from the given picked content [uri]. */
    fun sendVideo(uri: android.net.Uri) {
        viewModelScope.launch {
            _sendInFlight.value = true
            try {
                sendVideo(groupId, uri)
                _lastSendError.value = null
            } catch (e: Throwable) {
                _lastSendError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _sendInFlight.value = false
            }
        }
    }

    /** Media staged in the composer's preview strip, awaiting Send. */
    private val _pendingMedia = MutableStateFlow<List<PendingMediaItem>>(emptyList())
    val pendingMedia: StateFlow<List<PendingMediaItem>> = _pendingMedia.asStateFlow()

    /** Stage a picked item in the preview strip. */
    fun stagePendingMedia(thumbnail: android.graphics.Bitmap?, source: ChatMediaSource) {
        _pendingMedia.value = _pendingMedia.value + PendingMediaItem(
            id = java.util.UUID.randomUUID(), thumbnail = thumbnail, source = source
        )
    }

    /** Drop a staged item (its ✕ was tapped). */
    fun removePendingMedia(id: java.util.UUID) {
        _pendingMedia.value = _pendingMedia.value.filterNot { it.id == id }
    }

    /** Send the staged media as one album (or a single message if one
     *  item), then clear the strip. */
    fun sendPendingMedia() {
        val sources = _pendingMedia.value.map { it.source }
        if (sources.isEmpty()) return
        _pendingMedia.value = emptyList()
        viewModelScope.launch {
            _sendInFlight.value = true
            try {
                sendAlbum(groupId, sources)
                _lastSendError.value = null
            } catch (e: Throwable) {
                _lastSendError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _sendInFlight.value = false
            }
        }
    }

    /** Delete a message (the failed-media Delete action). */
    fun deleteMessage(messageId: java.util.UUID) {
        viewModelScope.launch { runCatching { deleteMessage(groupId, messageId) } }
    }

    fun clearError() { _lastSendError.value = null }

    /**
     * Emit READ receipts for incoming messages the user is now looking
     * at. No-op when the symmetric setting is off. Groups unacked
     * incoming ids by sender and ships one receipt per sender to that
     * sender's inbox key (resolved from the group's member profiles).
     */
    private suspend fun sendReadReceipts(snapshot: List<ChatMessage>) {
        if (!readReceiptsEnabled()) return
        val grp = group.value ?: return
        val bySender = LinkedHashMap<String, MutableList<java.util.UUID>>()
        for (message in snapshot) {
            if (message.direction == MessageDirection.INCOMING && message.id !in ackedReadIds) {
                bySender.getOrPut(message.senderBlsPubkeyHex) { mutableListOf() }.add(message.id)
            }
        }
        if (bySender.isEmpty()) return
        for ((senderHex, ids) in bySender) {
            val inbox = grp.memberProfiles[senderHex]?.inboxPublicKey ?: continue
            chatReceiptSender.send(
                kind = ChatReceiptPayload.Kind.READ,
                messageIds = ids,
                groupId = grp.groupIdBytes,
                recipientInboxKey = inbox,
            )
            ackedReadIds.addAll(ids)
        }
    }

    /**
     * Fire-and-forget retry on a previously-failed outgoing message.
     * No-op (silent) for unknown / non-failed / non-outgoing ids —
     * the interactor's [SendMessageInteractor.retry] enforces the
     * same gates. Failures during the retry's network round-trip
     * land back as `MessageStatus.FAILED` on the same row, which the
     * bubble's status glyph reflects naturally.
     */
    fun retry(messageId: java.util.UUID) {
        viewModelScope.launch {
            try {
                retryMessage(groupId, messageId)
            } catch (_: Throwable) {
                // Retry is silent — any thrown error from the
                // interactor surfaces as FAILED on the row instead.
            }
        }
    }
}
