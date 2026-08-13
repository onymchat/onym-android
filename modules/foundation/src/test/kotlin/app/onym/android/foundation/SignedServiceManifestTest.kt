package app.onym.android.foundation

import app.onym.android.foundation.ServiceManifestTestSigning.operatorKeyHex
import app.onym.android.foundation.ServiceManifestTestSigning.toLowercaseHex
import app.onym.android.foundation.ServiceManifestTestSigning.unsignedManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant

/**
 * Common-spine parsing: tolerant of seat-specific fields, strict on
 * the spine (componentId / seat / operator / validUntil), exact
 * rawBytes + digest, lossy offers. Mirrors the iOS
 * `SignedServiceManifest` semantics field for field.
 */
class SignedServiceManifestTest {

    private fun bytes(obj: JsonObject): ByteArray =
        Json.encodeToString(JsonElement.serializer(), obj).toByteArray(Charsets.UTF_8)

    // ─── spine accept ─────────────────────────────────────────────

    @Test
    fun parse_extractsCommonSpine() {
        val raw = bytes(unsignedManifest(validUntil = "2030-01-01T00:00:00Z"))
        val manifest = SignedServiceManifest.parse(raw)

        assertEquals("onym:component:test-courier", manifest.componentId)
        assertEquals("courier", manifest.seat)
        assertEquals("onym:key:$operatorKeyHex", manifest.operatorKey)
        assertEquals(operatorKeyHex, manifest.operatorPublicKeyHex)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), manifest.validUntil)
        assertNull(manifest.signatureBase64)
    }

    @Test
    fun parse_keepsExactRawBytesAndHashesThem() {
        val raw = bytes(unsignedManifest())
        val manifest = SignedServiceManifest.parse(raw)

        assertTrue(manifest.rawBytes.contentEquals(raw))
        val expected = "sha256:" +
            MessageDigest.getInstance("SHA-256").digest(raw).toLowercaseHex()
        assertEquals(expected, manifest.manifestHash)
    }

    @Test
    fun parse_toleratesUnknownSeatSpecificFields() {
        // `endpoints` (in the default fixture) plus arbitrary extras
        // must not break the spine decode — seats own their schemas.
        val obj = JsonObject(
            unsignedManifest() + mapOf(
                "policies" to buildJsonObject { put("maxSize", 42) },
            ),
        )
        val manifest = SignedServiceManifest.parse(bytes(obj))
        assertEquals("courier", manifest.seat)
    }

    @Test
    fun parse_acceptsAnyNonEmptySeat() {
        // New seats must not require a foundation change.
        val manifest = SignedServiceManifest.parse(bytes(unsignedManifest(seat = "blob-host")))
        assertEquals("blob-host", manifest.seat)
    }

    @Test
    fun parse_validUntilAcceptsOffsetsAndFractionalSeconds() {
        val fractional = SignedServiceManifest.parse(
            bytes(unsignedManifest(validUntil = "2030-01-01T00:00:00.500Z")),
        )
        assertEquals(Instant.parse("2030-01-01T00:00:00.500Z"), fractional.validUntil)

        val offset = SignedServiceManifest.parse(
            bytes(unsignedManifest(validUntil = "2030-01-01T02:00:00+02:00")),
        )
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), offset.validUntil)
    }

    // ─── spine rejections ─────────────────────────────────────────

    private fun assertManifestInvalid(raw: ByteArray) {
        assertThrows(ServiceManifestException.ManifestInvalid::class.java) {
            SignedServiceManifest.parse(raw)
        }
    }

    @Test
    fun parse_rejectsNonJson() =
        assertManifestInvalid("not json {".toByteArray())

    @Test
    fun parse_rejectsNonObjectTopLevel() =
        assertManifestInvalid("[1,2,3]".toByteArray())

    @Test
    fun parse_rejectsMissingComponentId() =
        assertManifestInvalid(bytes(JsonObject(unsignedManifest() - "componentId")))

    @Test
    fun parse_rejectsMalformedComponentIds() {
        // Wrong prefix, uppercase, illegal char, empty id, > 64 chars.
        for (bad in listOf(
            "component:test",
            "onym:component:Test",
            "onym:component:te_st",
            "onym:component:",
            "onym:component:" + "a".repeat(65),
        )) {
            assertManifestInvalid(bytes(unsignedManifest(componentId = bad)))
        }
    }

    @Test
    fun parse_rejectsMissingOrEmptySeat() {
        assertManifestInvalid(bytes(JsonObject(unsignedManifest() - "seat")))
        assertManifestInvalid(bytes(unsignedManifest(seat = "")))
    }

    @Test
    fun parse_rejectsMalformedOperatorKeys() {
        // Wrong prefix, uppercase hex, short hex, non-hex.
        for (bad in listOf(
            "key:$operatorKeyHex",
            "onym:key:" + operatorKeyHex.uppercase(),
            "onym:key:" + operatorKeyHex.dropLast(2),
            "onym:key:" + "g".repeat(64),
        )) {
            assertManifestInvalid(bytes(unsignedManifest(operatorKey = bad)))
        }
        assertManifestInvalid(bytes(JsonObject(unsignedManifest() - "operator")))
    }

    @Test
    fun parse_rejectsMalformedValidUntil() {
        assertManifestInvalid(bytes(unsignedManifest(validUntil = "tomorrow")))
        // Non-string validUntil.
        val obj = JsonObject(
            unsignedManifest() + mapOf("validUntil" to Json.parseToJsonElement("1735689600")),
        )
        assertManifestInvalid(bytes(obj))
    }

    // ─── lossy offers ─────────────────────────────────────────────

    @Test
    fun parse_absentOffersIsEmpty() {
        assertEquals(emptyList<ServiceOffer>(), SignedServiceManifest.parse(bytes(unsignedManifest())).offers)
    }

    @Test
    fun parse_offersNotAnArrayIsEmpty() {
        val obj = JsonObject(
            unsignedManifest() + mapOf("offers" to Json.parseToJsonElement("\"none\"")),
        )
        assertEquals(emptyList<ServiceOffer>(), SignedServiceManifest.parse(bytes(obj)).offers)
    }

    @Test
    fun parse_offersDecodeLossily() {
        val offers = listOf(
            // Valid free offer with a seat-specific service object.
            buildJsonObject {
                put("offerId", "free-tier")
                put("model", "free")
                putJsonObject("service") {
                    put("quota", 100)
                    put("api", "https://courier.example.com/free")
                }
            },
            // Valid subscription with a period.
            buildJsonObject {
                put("offerId", "monthly")
                put("model", "subscription")
                put("period", "monthly")
            },
            // Malformed: missing model.
            buildJsonObject { put("offerId", "broken") },
            // Malformed: missing offerId.
            buildJsonObject { put("model", "free") },
            // Malformed: empty offerId.
            buildJsonObject {
                put("offerId", "")
                put("model", "free")
            },
        )
        val raw = bytes(unsignedManifest(offers = offers))
        // Malformed entries (and non-object entries, below) are
        // skipped; the manifest still parses.
        val decoded = SignedServiceManifest.parse(raw).offers

        assertEquals(2, decoded.size)
        val free = decoded[0]
        assertEquals("free-tier", free.offerId)
        assertTrue(free.isFree)
        assertNull(free.period)
        // service is re-serialized compact + key-sorted.
        assertEquals(
            """{"api":"https://courier.example.com/free","quota":100}""",
            free.serviceJson,
        )
        val monthly = decoded[1]
        assertEquals("subscription", monthly.model)
        assertFalse(monthly.isFree)
        assertEquals("monthly", monthly.period)
        assertNull(monthly.serviceJson)
    }

    @Test
    fun parse_skipsNonObjectOfferEntries() {
        val raw = """
            {"componentId":"onym:component:x","seat":"courier",
             "operator":"onym:key:$operatorKeyHex",
             "offers":["free", 42, {"offerId":"ok","model":"free"}]}
        """.trimIndent().toByteArray()
        val decoded = SignedServiceManifest.parse(raw).offers
        assertEquals(listOf("ok"), decoded.map { it.offerId })
    }

    @Test
    fun offer_unknownModelGatesAsNotFree() {
        assertFalse(ServiceOffer(offerId = "x", model = "lifetime-nft").isFree)
    }

    // ─── equality ─────────────────────────────────────────────────

    @Test
    fun equality_isOverExactBytes() {
        val a = bytes(unsignedManifest())
        assertEquals(SignedServiceManifest.parse(a), SignedServiceManifest.parse(a.copyOf()))
        val b = bytes(unsignedManifest(seat = "notary"))
        assertFalse(SignedServiceManifest.parse(a) == SignedServiceManifest.parse(b))
    }
}
