package app.onym.android.foundation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

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
