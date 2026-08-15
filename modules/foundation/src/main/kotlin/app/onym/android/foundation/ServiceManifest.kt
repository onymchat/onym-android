package app.onym.android.foundation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Why a service manifest failed review. `reason` strings are
 * diagnostic, not part of any contract.
 *
 * Mirrors `ServiceManifestError` in onym-ios OnymFoundation.
 */
sealed class ServiceManifestException(message: String) : Exception(message) {
    /** Not JSON, top level not an object, or the common spine
     *  (componentId / seat / operator) is missing or malformed. */
    class ManifestInvalid(val reason: String) :
        ServiceManifestException("manifest invalid: $reason")

    /** Missing, unparseable, or non-verifying embedded signature. */
    class SignatureInvalid(val reason: String) :
        ServiceManifestException("signature invalid: $reason")

    /** `validUntil` is in the past. */
    class Expired(val validUntil: Instant) :
        ServiceManifestException("manifest expired at $validUntil")

    /** Fetched bytes hash differently than the digest the caller
     *  pinned (e.g. a catalog entry's `manifest.digest`). */
    class DigestMismatch(val expected: String, val actual: String) :
        ServiceManifestException("digest mismatch: expected $expected, actual $actual")
}

/**
 * A seat operator's manifest — courier, notary, authority, or any
 * future seat — parsed down to the **common spine** every seat
 * shares: `componentId`, `seat`, `operator`, optional `offers`,
 * optional `validUntil`, and the embedded `signature`.
 *
 * Destination seats own their schemas, so parsing is deliberately
 * tolerant: unknown fields are preserved untouched inside [rawBytes]
 * and simply not surfaced here. Seat adapters that need
 * seat-specific fields (endpoints, relay URLs, policies) decode them
 * from [rawBytes] with their own types.
 *
 * The exact bytes as served are retained alongside the decode —
 * signature verification and hash pinning are always over
 * [rawBytes], never over a re-encode.
 *
 * Mirrors `SignedServiceManifest` in onym-ios OnymFoundation.
 */
class SignedServiceManifest private constructor(
    /** `onym:component:<[a-z0-9-]{1,64}>`. */
    val componentId: String,
    /** The seat this operator fills (e.g. "courier", "notary",
     *  "authority"). Non-empty; otherwise unconstrained — new seats
     *  must not require a foundation change. */
    val seat: String,
    /** The operator key as published: `onym:key:<64-lowercase-hex>`. */
    val operatorKey: String,
    /** The 64-lowercase-hex Ed25519 public key from [operatorKey]. */
    val operatorPublicKeyHex: String,
    /** Offers decoded lossily from the manifest's `offers` array —
     *  malformed entries are skipped, absent array is empty. */
    val offers: List<ServiceOffer>,
    /** Manifest expiry, when the operator published one. */
    val validUntil: Instant?,
    /** The exact bytes as served. What gets verified, hashed, and
     *  pinned is always this, never a re-encode. */
    val rawBytes: ByteArray,
    /** `sha256:<64-lowercase-hex>` over [rawBytes], matching the
     *  digest format catalog entries pin. */
    val manifestHash: String,
    /** The embedded top-level `signature` (base64), if present.
     *  Verification lives in [ServiceManifestReviewer]. */
    internal val signatureBase64: String?,
) {
    companion object {
        /**
         * Parse the common spine out of exact manifest bytes. Performs
         * **no** signature or expiry checks — use
         * [ServiceManifestReviewer.review] to obtain a manifest fit
         * for consent.
         *
         * @throws ServiceManifestException.ManifestInvalid
         */
        fun parse(raw: ByteArray): SignedServiceManifest {
            val obj = ServiceManifestFormat.topLevelObject(raw)

            val componentId = obj.stringField("componentId")
                ?.takeIf(ServiceManifestFormat::isComponentId)
                ?: throw ServiceManifestException.ManifestInvalid("missing or malformed componentId")
            val seat = obj.stringField("seat")?.takeIf { it.isNotEmpty() }
                ?: throw ServiceManifestException.ManifestInvalid("missing or empty seat")
            val operatorKey = obj.stringField("operator")
                ?: throw ServiceManifestException.ManifestInvalid("missing or malformed operator key")
            val operatorHex = ServiceManifestFormat.operatorKeyHex(operatorKey)
                ?: throw ServiceManifestException.ManifestInvalid("missing or malformed operator key")

            val validUntil: Instant? = obj["validUntil"]?.let { element ->
                val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw ServiceManifestException.ManifestInvalid(
                        "validUntil is not an RFC 3339 timestamp",
                    )
                ServiceManifestFormat.instantFromRfc3339(text)
                    ?: throw ServiceManifestException.ManifestInvalid(
                        "validUntil is not an RFC 3339 timestamp",
                    )
            }

            return SignedServiceManifest(
                componentId = componentId,
                seat = seat,
                operatorKey = operatorKey,
                operatorPublicKeyHex = operatorHex,
                offers = ServiceOffer.decodeLossy(obj["offers"]),
                validUntil = validUntil,
                rawBytes = raw,
                manifestHash = ServiceManifestFormat.sha256Digest(raw),
                signatureBase64 = obj.stringField("signature"),
            )
        }

        private fun JsonObject.stringField(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Every surfaced property is a pure function of [rawBytes], so
     *  equality (like verification and pinning) is over the exact
     *  bytes. */
    override fun equals(other: Any?): Boolean =
        other is SignedServiceManifest && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = rawBytes.contentHashCode()
}

/**
 * Canonical signing bytes for service manifests, matching the
 * cross-language precedent set by the moderation seat
 * (`authority/src/canonical.rs`) and the Discovery profile
 * (`DiscoveryCanonical` in `:discovery`):
 *
 *  1. parse the document as UTF-8 JSON; the top level must be an
 *     object;
 *  2. remove the `signature` field **structurally** — never by string
 *     surgery, because a planted copy of the signature elsewhere in
 *     the document would make textual removal forgeable;
 *  3. re-serialize compactly with all object keys, at every nesting
 *     level, sorted by UTF-8 byte order; `/` is not escaped (kotlinx
 *     `Json` output is compact and never emits `\/`).
 *
 * Implemented locally on purpose: seat packages depend on
 * `:foundation` and must not gain a `:discovery` dependency to
 * verify a manifest.
 */
object ServiceManifestCanonical {

    /**
     * The bytes a manifest's embedded Ed25519 signature is made over.
     *
     * @throws ServiceManifestException.ManifestInvalid if [raw] is not
     *   valid JSON or the top level is not an object.
     */
    fun signingBytes(raw: ByteArray): ByteArray {
        val obj = ServiceManifestFormat.topLevelObject(raw)
        val withoutSignature = JsonObject(obj.filterKeys { it != "signature" })
        return Json.encodeToString(
            JsonElement.serializer(),
            sortKeysRecursively(withoutSignature),
        ).toByteArray(Charsets.UTF_8)
    }

    /** Rebuild [element] with every object's keys sorted by UTF-8
     *  byte order, at every nesting level. Arrays keep their order
     *  (order is data); primitives pass through untouched. */
    internal fun sortKeysRecursively(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedWith(compareBy(UTF8_BYTE_ORDER) { it.key })
                .associate { (key, value) -> key to sortKeysRecursively(value) },
        )
        is JsonArray -> JsonArray(element.map { sortKeysRecursively(it) })
        else -> element
    }

    /**
     * UTF-8 byte order, not `String.compareTo` — the two differ for
     * strings containing surrogate pairs (UTF-16 code-unit order sorts
     * U+E000..U+FFFF *after* supplementary characters; UTF-8 byte
     * order sorts them before). Manifest keys are ASCII lowercase
     * where both orders coincide, but the comparator is written to
     * the spec, not to the happy case.
     */
    private val UTF8_BYTE_ORDER = Comparator<String> { a, b ->
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        val limit = minOf(aBytes.size, bBytes.size)
        for (i in 0 until limit) {
            val diff = (aBytes[i].toInt() and 0xFF) - (bBytes[i].toInt() and 0xFF)
            if (diff != 0) return@Comparator diff
        }
        aBytes.size - bBytes.size
    }
}

/** Identifier / digest syntax shared by every seat manifest. */
internal object ServiceManifestFormat {

    /** Parse [raw] as UTF-8 JSON and require a top-level object.
     *  @throws ServiceManifestException.ManifestInvalid */
    fun topLevelObject(raw: ByteArray): JsonObject {
        val element = try {
            Json.parseToJsonElement(String(raw, Charsets.UTF_8))
        } catch (_: Exception) {
            throw ServiceManifestException.ManifestInvalid("document is not valid JSON")
        }
        return element as? JsonObject
            ?: throw ServiceManifestException.ManifestInvalid("top level is not a JSON object")
    }

    /** `onym:key:<64-lowercase-hex>` → the hex, or null. */
    fun operatorKeyHex(value: String): String? {
        if (!value.startsWith("onym:key:")) return null
        val hex = value.removePrefix("onym:key:")
        if (hex.length != 64 || !isLowercaseHex(hex)) return null
        return hex
    }

    /** `onym:component:<[a-z0-9-]{1,64}>`. */
    fun isComponentId(value: String): Boolean {
        if (!value.startsWith("onym:component:")) return false
        val id = value.removePrefix("onym:component:")
        if (id.length !in 1..64) return false
        return id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

    /** `sha256:` + 64 lowercase hex. */
    fun isDigest(value: String): Boolean {
        if (!value.startsWith("sha256:")) return false
        val hex = value.removePrefix("sha256:")
        return hex.length == 64 && isLowercaseHex(hex)
    }

    fun isLowercaseHex(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** `sha256:<hex>` over exact bytes. */
    fun sha256Digest(data: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** Lowercase-hex string → bytes, or null on odd length / non-hex. */
    fun bytesFromLowercaseHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || !isLowercaseHex(hex)) return null
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }

    /** RFC 3339, seconds or fractional-seconds precision, `Z` or
     *  numeric offset — matching iOS `ISO8601DateFormatter`. */
    fun instantFromRfc3339(value: String): Instant? = try {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    /** Base64 accepting padded or unpadded input. */
    fun decodeBase64AcceptingUnpadded(value: String): ByteArray? {
        val padded = when (val remainder = value.length % 4) {
            0 -> value
            1 -> return null
            else -> value + "=".repeat(4 - remainder)
        }
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
