package app.onym.android.chats

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An encrypted image attached to a chat message. Android twin of iOS
 * `ChatImageAttachment`.
 *
 * The image is AES-GCM-encrypted with a per-image random key and the
 * ciphertext is uploaded to a Blossom blob server (`blossom.onym.app`),
 * addressed by the SHA-256 of the stored bytes. This descriptor travels
 * inside the already-sealed, per-recipient [ChatMessagePayload]:
 *
 *  - [sha256] locates the blob (`GET <server>/<sha256>`) + verifies it.
 *  - [encKey] is the 32-byte AES-GCM key (in the clear only inside the
 *    sealed envelope; the blob is opaque ciphertext, stored as
 *    nonce ‖ ciphertext ‖ tag so no separate nonce is carried).
 *  - [blurhash] renders instantly as a placeholder (blurhash-only, no
 *    inline thumbnail, to keep the sealed envelope inside relay size
 *    limits).
 *  - [width]/[height] are the decoded pixel dimensions for aspect ratio.
 *  - [server] is the blob server base URL; `null` = app default.
 *
 * Additive on the wire: [ChatMessagePayload.attachment] is optional.
 */
@Serializable
data class ChatImageAttachment(
    val sha256: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Int,
    val width: Int,
    val height: Int,
    @SerialName("enc_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val encKey: ByteArray,
    val blurhash: String,
    val server: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatImageAttachment) return false
        return sha256 == other.sha256 &&
            mimeType == other.mimeType &&
            byteSize == other.byteSize &&
            width == other.width &&
            height == other.height &&
            encKey.contentEquals(other.encKey) &&
            blurhash == other.blurhash &&
            server == other.server
    }

    override fun hashCode(): Int {
        var h = sha256.hashCode()
        h = 31 * h + mimeType.hashCode()
        h = 31 * h + byteSize
        h = 31 * h + width
        h = 31 * h + height
        h = 31 * h + encKey.contentHashCode()
        h = 31 * h + blurhash.hashCode()
        h = 31 * h + (server?.hashCode() ?: 0)
        return h
    }
}
