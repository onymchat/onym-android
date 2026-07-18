package app.onym.android.chats

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An encrypted video attached to a chat message. Android twin of iOS
 * `ChatVideoAttachment`.
 *
 * Same envelope model as [ChatImageAttachment]: the (720p-transcoded)
 * video is AES-GCM-encrypted with a per-video random key and the
 * ciphertext is uploaded to Blossom, addressed by the SHA-256 of the
 * stored bytes. This descriptor travels inside the already-sealed,
 * per-recipient [ChatMessagePayload].
 *
 * A video can't render a cheap blurhash-only placeholder that reads
 * well and it's too big to fetch eagerly, so a **separate encrypted
 * poster blob** ships alongside it: [poster] is a full
 * [ChatImageAttachment] (its own blob + key + blurhash + dimensions)
 * carrying the first frame. The bubble renders the poster instantly and
 * only downloads the (large) video blob when the user taps play.
 *
 *  - [sha256] locates the video blob (`GET <server>/<sha256>`) + verifies it.
 *  - [encKey] is the 32-byte AES-GCM key for the video blob (blob is
 *    stored nonce ‖ ciphertext ‖ tag, so no separate nonce is carried).
 *  - [durationSeconds] drives the duration pill on the bubble poster.
 *  - [width]/[height] are the transcoded pixel dimensions for aspect ratio.
 *  - [server] is the blob server base URL; `null` = app default.
 *
 * Additive on the wire: [ChatMessagePayload.videoAttachment] is optional.
 */
@Serializable
data class ChatVideoAttachment(
    val sha256: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Int,
    val width: Int,
    val height: Int,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("enc_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val encKey: ByteArray,
    val poster: ChatImageAttachment,
    val server: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatVideoAttachment) return false
        return sha256 == other.sha256 &&
            mimeType == other.mimeType &&
            byteSize == other.byteSize &&
            width == other.width &&
            height == other.height &&
            durationSeconds == other.durationSeconds &&
            encKey.contentEquals(other.encKey) &&
            poster == other.poster &&
            server == other.server
    }

    override fun hashCode(): Int {
        var h = sha256.hashCode()
        h = 31 * h + mimeType.hashCode()
        h = 31 * h + byteSize
        h = 31 * h + width
        h = 31 * h + height
        h = 31 * h + durationSeconds.hashCode()
        h = 31 * h + encKey.contentHashCode()
        h = 31 * h + poster.hashCode()
        h = 31 * h + (server?.hashCode() ?: 0)
        return h
    }
}
