package app.onym.android.moderation

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration every moderation wire type uses.
 *
 * - `explicitNulls = false` — absent optionals are omitted, never
 *   `null`: the backend's serde types treat the two differently, and
 *   the signed forms must not carry `null` members.
 * - `ignoreUnknownKeys = true` — tolerant decode, so a backend that
 *   grows a field does not brick every client.
 */
object ModerationJson {
    val json: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}

/** RFC 3339 UTC, seconds precision — `2026-08-08T12:00:00Z` — the form
 * every moderation timestamp crosses the wire (and enters signed
 * payloads) in. */
object Rfc3339InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Rfc3339Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(format(value))
    }

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())

    fun format(value: Instant): String = value.truncatedTo(ChronoUnit.SECONDS).toString()

    fun parse(raw: String): Instant = try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("not an RFC 3339 timestamp: $raw", e)
    }
}

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

/** Lowercase hex SHA-256 — the repo's content-address form (manifest
 * hashes, mandate refs). */
fun sha256Hex(bytes: ByteArray): String =
    sha256(bytes).joinToString("") { "%02x".format(it) }
