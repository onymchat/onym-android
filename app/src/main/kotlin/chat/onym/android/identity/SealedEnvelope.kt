package chat.onym.android.identity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * NIP-XX sealed envelope wire format. Mirrors the JSON shape that
 * `stellar-mls/clients/android/StellarChat/.../crypto/GroupCrypto.kt`
 * writes for `kind = 34113` invitations and that iOS PR #17 expects:
 *
 * ```json
 * {
 *   "version": 1,
 *   "scheme": "x25519-aes-256-gcm-v1",
 *   "ephemeral_public_key": "<base64 32B>",
 *   "ephemeral_key_signature": "<base64 64B Ed25519, optional>",
 *   "sender_ed25519_public_key": "<base64 32B, optional>",
 *   "nonce": "<base64 12B>",
 *   "ciphertext": "<base64 N B>",
 *   "authentication_tag": "<base64 16B>"
 * }
 * ```
 *
 * Field names are **load-bearing** for cross-platform interop with
 * stellar-mls senders + iOS receivers — keep the snake_case
 * `@SerialName` overrides verbatim.
 *
 * `ByteArray` fields are encoded as base64 strings (matching
 * Foundation's default `Data` JSON encoding on iOS); see
 * [Base64ByteArraySerializer].
 *
 * Mirrors `SealedEnvelope.swift` from onym-ios PR #17.
 */
@Serializable
data class SealedEnvelope(
    val version: Int = 1,
    val scheme: String,
    @SerialName("ephemeral_public_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val ephemeralPublicKey: ByteArray? = null,
    @SerialName("ephemeral_key_signature")
    @Serializable(with = Base64ByteArraySerializer::class)
    val ephemeralKeySignature: ByteArray? = null,
    @SerialName("sender_ed25519_public_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val senderEd25519PublicKey: ByteArray? = null,
    @Serializable(with = Base64ByteArraySerializer::class)
    val nonce: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val ciphertext: ByteArray,
    @SerialName("authentication_tag")
    @Serializable(with = Base64ByteArraySerializer::class)
    val authenticationTag: ByteArray,
) {
    // Default data-class equals/hashCode use reference equality for
    // ByteArray; override with content-based comparison so test
    // assertions on round-tripped envelopes work.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedEnvelope) return false
        return version == other.version &&
            scheme == other.scheme &&
            (ephemeralPublicKey?.contentEquals(other.ephemeralPublicKey) ?: (other.ephemeralPublicKey == null)) &&
            (ephemeralKeySignature?.contentEquals(other.ephemeralKeySignature) ?: (other.ephemeralKeySignature == null)) &&
            (senderEd25519PublicKey?.contentEquals(other.senderEd25519PublicKey) ?: (other.senderEd25519PublicKey == null)) &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            authenticationTag.contentEquals(other.authenticationTag)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + scheme.hashCode()
        result = 31 * result + (ephemeralPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (ephemeralKeySignature?.contentHashCode() ?: 0)
        result = 31 * result + (senderEd25519PublicKey?.contentHashCode() ?: 0)
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authenticationTag.contentHashCode()
        return result
    }
}

/**
 * Encodes `ByteArray` as a base64 string. Matches iOS Foundation's
 * default `Data` JSON encoding (which emits a base64 string, not a
 * JSON array of integers like kotlinx.serialization's default).
 *
 * Used per-field via `@Serializable(with = Base64ByteArraySerializer::class)`
 * so it doesn't bleed into other `ByteArray` fields elsewhere in the
 * codebase that may want a different encoding.
 */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}
