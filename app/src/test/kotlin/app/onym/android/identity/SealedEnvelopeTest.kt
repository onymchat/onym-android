package app.onym.android.identity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON contract for the [SealedEnvelope] wire format. Cross-platform
 * interop with stellar-mls senders + iOS receivers rides on:
 *
 *  - snake_case `@SerialName` overrides on every camelCase Kotlin
 *    property,
 *  - base64 string encoding for every `ByteArray` (matching iOS
 *    Foundation's default `Data` JSON encoding), and
 *  - tolerant decoding of envelopes that omit the optional M-5
 *    Ed25519 signature fields.
 *
 * Mirrors `SealedEnvelopeTests.swift` from onym-ios PR #17.
 */
class SealedEnvelopeTest {

    private val format = Json { encodeDefaults = true }

    @Test
    fun toJson_usesSnakeCaseFieldNames() {
        val envelope = SealedEnvelope(
            scheme = "x25519-aes-256-gcm-v1",
            ephemeralPublicKey = ByteArray(32) { 0x11 },
            ephemeralKeySignature = ByteArray(64) { 0x22 },
            senderEd25519PublicKey = ByteArray(32) { 0x33 },
            nonce = ByteArray(12) { 0x44 },
            ciphertext = ByteArray(8) { 0x55 },
            authenticationTag = ByteArray(16) { 0x66 },
        )
        val json = format.encodeToString(SealedEnvelope.serializer(), envelope)

        // Required snake_case keys (interop contract).
        for (key in listOf(
            "\"ephemeral_public_key\"",
            "\"ephemeral_key_signature\"",
            "\"sender_ed25519_public_key\"",
            "\"authentication_tag\"",
        )) {
            assertTrue("expected `$key` in $json", json.contains(key))
        }
        // CamelCase variants must NOT appear — the wire contract is
        // snake_case and a regression here would break stellar-mls /
        // iOS interop silently.
        for (forbidden in listOf(
            "ephemeralPublicKey",
            "ephemeralKeySignature",
            "senderEd25519PublicKey",
            "authenticationTag",
        )) {
            assertTrue(
                "field name `$forbidden` leaked into wire JSON: $json",
                !json.contains(forbidden),
            )
        }
    }

    @Test
    fun roundtrip_preservesAllFields() {
        val original = SealedEnvelope(
            scheme = "x25519-aes-256-gcm-v1",
            ephemeralPublicKey = ByteArray(32) { it.toByte() },
            ephemeralKeySignature = ByteArray(64) { (it + 1).toByte() },
            senderEd25519PublicKey = ByteArray(32) { (it + 2).toByte() },
            nonce = ByteArray(12) { (it + 3).toByte() },
            ciphertext = ByteArray(40) { (it + 4).toByte() },
            authenticationTag = ByteArray(16) { (it + 5).toByte() },
        )
        val json = format.encodeToString(SealedEnvelope.serializer(), original)
        val decoded = format.decodeFromString(SealedEnvelope.serializer(), json)
        assertEquals(original, decoded)
        // Spot-check a few `ByteArray` fields explicitly so a future
        // bug in the data class equals doesn't mask a real mismatch.
        assertArrayEquals(original.ephemeralPublicKey, decoded.ephemeralPublicKey)
        assertArrayEquals(original.authenticationTag, decoded.authenticationTag)
    }

    @Test
    fun decodes_envelopeWithoutOptionalSignatureFields() {
        // Sender omitted M-5 signature fields entirely — must decode
        // with the optional fields as null, not error out.
        val json = """
            {
              "version": 1,
              "scheme": "x25519-aes-256-gcm-v1",
              "ephemeral_public_key": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
              "nonce": "AQIDBAUGBwgJCgsM",
              "ciphertext": "AQID",
              "authentication_tag": "AAECAwQFBgcICQoLDA0ODw=="
            }
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(SealedEnvelope.serializer(), json)
        assertEquals("x25519-aes-256-gcm-v1", decoded.scheme)
        assertNotNull(decoded.ephemeralPublicKey)
        assertNull(decoded.ephemeralKeySignature)
        assertNull(decoded.senderEd25519PublicKey)
        assertEquals(12, decoded.nonce.size)
        assertEquals(16, decoded.authenticationTag.size)
    }
}
