package app.onym.android.chats

import kotlinx.serialization.Serializable

/**
 * One item in a multi-media (album) chat message — either an image or a
 * video. Android twin of iOS `ChatMediaAttachment`.
 *
 * Encoded as `{ "kind": "image"|"video", "image": {…} | "video": {…} }`,
 * matching the iOS discriminated union, so albums round-trip
 * cross-platform. Exactly one of [image] / [video] is set for a given
 * [kind]. Albums carry `[ChatMediaAttachment]`; single-media messages
 * keep using the flat `attachment` / `videoAttachment` fields.
 */
@Serializable
data class ChatMediaAttachment(
    val kind: String,
    val image: ChatImageAttachment? = null,
    val video: ChatVideoAttachment? = null,
) {
    /** The poster/image used to render this item's thumbnail. */
    val thumbnail: ChatImageAttachment
        get() = image ?: video!!.poster

    val isVideo: Boolean get() = kind == KIND_VIDEO

    /** Every blob SHA-256 this item references (for outbox eviction). */
    val blobShas: List<String>
        get() = when {
            image != null -> listOf(image.sha256)
            video != null -> listOf(video.poster.sha256, video.sha256)
            else -> emptyList()
        }

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"

        fun image(attachment: ChatImageAttachment) =
            ChatMediaAttachment(kind = KIND_IMAGE, image = attachment)

        fun video(attachment: ChatVideoAttachment) =
            ChatMediaAttachment(kind = KIND_VIDEO, video = attachment)
    }
}

/** A picked media item awaiting send — the raw source before encoding.
 *  Images arrive as bytes; videos as a content [android.net.Uri]. */
sealed interface ChatMediaSource {
    data class Image(val data: ByteArray) : ChatMediaSource {
        override fun equals(other: Any?) =
            other is Image && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
    }
    data class Video(val uri: android.net.Uri) : ChatMediaSource
}
