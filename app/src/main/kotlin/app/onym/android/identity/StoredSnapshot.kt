package app.onym.android.identity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Persisted identity material — the only thing on disk. Decoded into
 * an [Identity] at load time by [IdentityRepository] (which then derives
 * stellar / inbox pubkeys + mnemonic on top).
 *
 * Single JSON blob in EncryptedSharedPreferences. ByteArrays are
 * Base64-encoded — matches how Swift's `JSONEncoder` serializes `Data`
 * by default, so the JSON shape is byte-identical to what iOS would
 * write. (Cross-platform interop on the *snapshot file* is not a
 * promised contract — the recovery phrase is the contract — but
 * mirroring the iOS shape keeps mental overhead low and makes the
 * Kotlin port easier to verify.)
 */
@Serializable
data class StoredSnapshot(
    /**
     * 16 bytes (128-bit BIP39 entropy). `null` only if the identity was
     * imported from raw key material without an associated mnemonic —
     * not currently produced by the repository, but tolerated on read
     * so the shape stays forward-compatible with a future "import raw
     * secret" path.
     */
    @Serializable(with = ByteArrayBase64Serializer::class)
    val entropy: ByteArray?,
    /** 32-byte secp256k1 secret key for Nostr signing. */
    @Serializable(with = ByteArrayBase64Serializer::class)
    val nostrSecretKey: ByteArray,
    /** 32-byte BLS12-381 Fr scalar for SEP group membership. */
    @Serializable(with = ByteArrayBase64Serializer::class)
    val blsSecretKey: ByteArray,
    /** User-supplied display name. Set at add-identity time, surfaced
     *  via [IdentitySummary.name] in the UI. Default empty string for
     *  the bootstrap-from-zero path; the repository auto-fills with
     *  "Identity N" when persisting a blank name. */
    val name: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredSnapshot) return false
        val entropyEqual = when {
            entropy == null && other.entropy == null -> true
            entropy == null || other.entropy == null -> false
            else -> entropy.contentEquals(other.entropy)
        }
        return entropyEqual &&
                nostrSecretKey.contentEquals(other.nostrSecretKey) &&
                blsSecretKey.contentEquals(other.blsSecretKey) &&
                name == other.name
    }

    override fun hashCode(): Int {
        var result = entropy?.contentHashCode() ?: 0
        result = 31 * result + nostrSecretKey.contentHashCode()
        result = 31 * result + blsSecretKey.contentHashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}

/**
 * Encodes ByteArray as Base64-string in JSON. Matches Swift
 * `JSONEncoder`'s default `Data` representation.
 */
internal object ByteArrayBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteArrayBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.getDecoder().decode(decoder.decodeString())
    }
}
