package app.onym.android.chats

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An encrypted voice message attached to a chat message. Android twin of
 * iOS `ChatVoiceAttachment`.
 *
 * Same envelope model as [ChatImageAttachment] / [ChatVideoAttachment]:
 * the recorded AAC (`.m4a`) clip is AES-GCM-encrypted with a per-clip
 * random key and the ciphertext is uploaded to Blossom, addressed by the
 * SHA-256 of the stored bytes. This descriptor travels inside the
 * already-sealed, per-recipient [ChatMessagePayload].
 *
 * There's no visual thumbnail — the bubble renders a play button, a static
 * [waveform], and the [durationSeconds] pill from the descriptor alone, and
 * only downloads the (small) audio blob when the user taps play.
 *
 *  - [sha256] locates the audio blob (`GET <server>/<sha256>`) + verifies it.
 *  - [encKey] is the 32-byte AES-GCM key for the blob (stored nonce ‖
 *    ciphertext ‖ tag, so no separate nonce is carried).
 *  - [durationSeconds] drives the `m:ss` pill on the bubble.
 *  - [waveform] is a small fixed-count array of normalized amplitude
 *    samples (0…255), precomputed at record time so the bars render before
 *    (and without ever) downloading the audio. Wire-compatible with iOS's
 *    `[UInt8]` (both emit a flat JSON int array).
 *  - [server] is the blob server base URL; `null` = app default.
 *
 * Additive on the wire: [ChatMessagePayload.voiceAttachment] is optional.
 */
@Serializable
data class ChatVoiceAttachment(
    val sha256: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Int,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("enc_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val encKey: ByteArray,
    val waveform: List<Int>,
    val server: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatVoiceAttachment) return false
        return sha256 == other.sha256 &&
            mimeType == other.mimeType &&
            byteSize == other.byteSize &&
            durationSeconds == other.durationSeconds &&
            encKey.contentEquals(other.encKey) &&
            waveform == other.waveform &&
            server == other.server
    }

    override fun hashCode(): Int {
        var h = sha256.hashCode()
        h = 31 * h + mimeType.hashCode()
        h = 31 * h + byteSize
        h = 31 * h + durationSeconds.hashCode()
        h = 31 * h + encKey.contentHashCode()
        h = 31 * h + waveform.hashCode()
        h = 31 * h + (server?.hashCode() ?: 0)
        return h
    }
}
