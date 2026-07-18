package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import java.util.UUID

/**
 * Domain shape of one chat message, as the Android app understands
 * it. Mirrors `ChatMessage.swift` from onym-ios PR #148.
 *
 * Two kinds of fields:
 *  - **Identifying / queryable** ([id], [groupId], [ownerIdentityId],
 *    [sentAtMillis], [direction], [status], [groupType]) stay plain
 *    on disk so the DAO can sort and filter without round-tripping
 *    through the encryption boundary.
 *  - **Sensitive** ([senderBlsPubkeyHex], [body]) ride encrypted —
 *    see [PersistedMessage] for the column split.
 *
 * [direction] is the local-view distinction (incoming = received
 * from someone else, outgoing = sent by this device); [status] is
 * the lifecycle stage (pending → sent / failed, or received). Kept
 * separate so a `PENDING / OUTGOING` row can survive a relaunch
 * during a send.
 */
data class ChatMessage(
    /** Stable per-message id. Matches
     *  [ChatMessagePayload.messageId] on the wire so the receiver
     *  can dedup re-deliveries. */
    val id: UUID,
    /** 64-char lowercase hex of the 32-byte group ID. Matches
     *  [app.onym.android.group.ChatGroup.id]. */
    val groupId: String,
    /** [app.onym.android.identity.IdentityId.value]. Drives the
     *  per-identity filter on [MessageRepository.snapshots]. */
    val ownerIdentityId: String,
    /** 96-char lowercase BLS pubkey hex. Matches
     *  [app.onym.android.group.ChatGroup.memberProfiles] keying so
     *  the chat screen can look up the sender's alias with one
     *  dictionary read. */
    val senderBlsPubkeyHex: String,
    val body: String,
    /** Milliseconds since Unix epoch. */
    val sentAtMillis: Long,
    val direction: MessageDirection,
    val status: MessageStatus,
    /** The message this one replies to, if any. Mirrors
     *  [ChatMessagePayload.replyToMessageId]. Only the target ID is
     *  stored — the UI resolves the quoted sender + body by looking
     *  this up among the group's other messages at render time, so a
     *  target that isn't on this device renders as "Message
     *  unavailable" instead of carrying a stale copy of its text. */
    val replyToMessageId: UUID? = null,
    val groupType: SepGroupType,
    /** Encrypted image attached to this message, if any. Mirrors
     *  [ChatMessagePayload.attachment]; `body` is the caption when this
     *  is present. Fetched + decrypted lazily at render time
     *  ([ChatImageLoader]), not stored inline. */
    val imageAttachment: ChatImageAttachment? = null,
    /** Encrypted video attached to this message, if any. Mirrors
     *  [ChatMessagePayload.videoAttachment]; `body` is the caption when
     *  this is present. The poster loads eagerly like an image; the video
     *  blob only downloads on play ([ChatVideoLoader]). */
    val videoAttachment: ChatVideoAttachment? = null,
    /** A multi-media album (2+ items) attached to this message. Mirrors
     *  [ChatMessagePayload.attachments]. `null` for text + single-media
     *  messages (which use [imageAttachment] / [videoAttachment]). */
    val albumAttachments: List<ChatMediaAttachment>? = null,
    /** Encrypted voice message attached to this message, if any. Mirrors
     *  [ChatMessagePayload.voiceAttachment]. The bubble renders the waveform
     *  + duration from the descriptor and only downloads the audio blob on
     *  play. Mutually exclusive with the image/video/album fields. */
    val voiceAttachment: ChatVoiceAttachment? = null,
) {
    /** Canonical media list for rendering: the album when present, else
     *  the single image/video wrapped in a one-element list, else empty. */
    val media: List<ChatMediaAttachment>
        get() = when {
            !albumAttachments.isNullOrEmpty() -> albumAttachments
            imageAttachment != null -> listOf(ChatMediaAttachment.image(imageAttachment))
            videoAttachment != null -> listOf(ChatMediaAttachment.video(videoAttachment))
            else -> emptyList()
        }

    /** One-line preview for the chat-list row subtitle. Media messages
     *  (which carry no/empty body) render a label; text renders its body.
     *  Own messages get a "You: " prefix to disambiguate in a group.
     *  Mirrors iOS `ChatMessage.chatListPreview`. */
    val chatListPreview: String
        get() {
            val content = when {
                voiceAttachment != null -> "Voice message"
                !albumAttachments.isNullOrEmpty() -> "Album"
                videoAttachment != null -> "Video"
                imageAttachment != null -> "Photo"
                else -> body
            }
            return if (direction == MessageDirection.OUTGOING) "You: $content" else content
        }
}
