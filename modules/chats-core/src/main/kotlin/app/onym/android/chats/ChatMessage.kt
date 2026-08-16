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
    /** Non-null when this row is a locally-minted system notice
     *  ("Alice joined") rather than something a person typed. `null` is
     *  the overwhelmingly common case and means "ordinary message".
     *
     *  Deliberately absent from [ChatMessagePayload]: system rows are
     *  **never** carried on the wire. Each device mints its own copy
     *  from an event it independently verified (the admin from its own
     *  approve, members from a signature- and chain-checked
     *  [app.onym.android.group.MemberAnnouncementPayload], the joiner
     *  from its verified invitation). A wire field here would let any
     *  peer forge "X joined" history. */
    val systemEvent: ChatSystemEvent? = null,
    /** Detached Ed25519 signature (standard base64) over the message's
     *  canonical [ChatModerationProof] preimage. Mirrors
     *  [ChatMessagePayload.moderationAuthenticityProof]. Stored on
     *  incoming rows so a recipient can later disclose the message to
     *  a moderation Authority, and on outgoing rows so [retry] re-ships
     *  the identical proof (Ed25519 is deterministic, but re-signing a
     *  drifted preimage would break the receivers' stored copy). */
    val moderationAuthenticityProof: String? = null,
) {
    /** True for locally-minted system notices. Reads better than
     *  `systemEvent != null` at the several call sites that only care
     *  whether the row is a notice, not which kind. */
    val isSystem: Boolean get() = systemEvent != null

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
            // A notice has no preview of its own here: the sentence is
            // assembled from `systemEvent` in the UI, where string
            // resources are reachable. Returning prose from this module
            // would mean hardcoded English (this getter already carries
            // five such strings — a known gap, not one to widen) with no
            // `values-ru` twin and no lint to catch it.
            if (isSystem) return ""
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
