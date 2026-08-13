package app.onym.android.foundation

import app.onym.android.foundation.ServiceManifestTestSigning.defaultSignedManifest
import app.onym.android.foundation.ServiceManifestTestSigning.signedManifestBytes
import app.onym.android.foundation.ServiceManifestTestSigning.strangerPrivateKey
import app.onym.android.foundation.ServiceManifestTestSigning.toLowercaseHex
import app.onym.android.foundation.ServiceManifestTestSigning.unsignedManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant

/**
 * Hard-enforced manifest review: embedded Ed25519 signature over the
 * canonical bytes against the manifest's own operator key, optional
 * digest pin, injected-clock expiry. No accept-anyway path.
 */
class ServiceManifestReviewerTest {

    private val reviewer = ServiceManifestReviewer()
    private val now: Instant = Instant.parse("2026-08-13T12:00:00Z")

    private fun digestOf(raw: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(raw).toLowercaseHex()

    // ─── accept ───────────────────────────────────────────────────

    @Test
    fun review_acceptsProperlySignedManifest() {
        val raw = defaultSignedManifest()
        val reviewed = reviewer.review(raw, now = now)

        // The token wraps the EXACT bytes that were verified.
        assertTrue(reviewed.signedManifest.rawBytes.contentEquals(raw))
        assertEquals(digestOf(raw), reviewed.signedManifest.manifestHash)
        assertEquals("onym:component:test-courier", reviewed.signedManifest.componentId)
    }

    @Test
    fun review_acceptsWithMatchingDigestPin() {
        val raw = defaultSignedManifest()
        val reviewed = reviewer.review(raw, expectedDigest = digestOf(raw), now = now)
        assertEquals(digestOf(raw), reviewed.signedManifest.manifestHash)
    }

    @Test
    fun review_acceptsUnexpiredManifest() {
        val raw = signedManifestBytes(unsignedManifest(validUntil = "2026-12-31T00:00:00Z"))
        reviewer.review(raw, now = now)
    }

    @Test
    fun review_acceptsUnpaddedSignatureBase64() {
        val raw = defaultSignedManifest()
        val text = String(raw, Charsets.UTF_8)
        // A 64-byte Ed25519 signature base64-encodes with '=' padding;
        // operators may publish the unpadded form.
        assertTrue(text.contains("=\""))
        val unpadded = text.replace(Regex("=+\""), "\"").toByteArray()
        reviewer.review(unpadded, now = now)
    }

    @Test
    fun review_acceptsPlantedNestedSignatureCopy() {
        // A "signature" key nested in seat-specific data is data, not
        // the manifest signature — structural top-level removal only.
        val unsigned = JsonObject(
            unsignedManifest() + mapOf(
                "attestation" to JsonObject(mapOf("signature" to JsonPrimitive("bm90LXRoZS1zaWc="))),
            ),
        )
        reviewer.review(signedManifestBytes(unsigned), now = now)
    }

    // ─── signature failures ───────────────────────────────────────

    @Test
    fun review_rejectsTamperedBytes() {
        val raw = defaultSignedManifest()
        val tampered = String(raw, Charsets.UTF_8)
            .replace("https://courier.example.com/v1", "https://evil.example.com/v1")
            .toByteArray()
        assertThrows(ServiceManifestException.SignatureInvalid::class.java) {
            reviewer.review(tampered, now = now)
        }
    }

    @Test
    fun review_rejectsWrongKeySignature() {
        // Signed by a stranger, but the manifest claims the operator's
        // key — the embedded signature must verify against `operator`.
        val raw = signedManifestBytes(unsignedManifest(), key = strangerPrivateKey)
        assertThrows(ServiceManifestException.SignatureInvalid::class.java) {
            reviewer.review(raw, now = now)
        }
    }

    @Test
    fun review_rejectsMissingSignature() {
        val raw = """
            {"componentId":"onym:component:test-courier","seat":"courier",
             "operator":"onym:key:${ServiceManifestTestSigning.operatorKeyHex}"}
        """.trimIndent().toByteArray()
        assertThrows(ServiceManifestException.SignatureInvalid::class.java) {
            reviewer.review(raw, now = now)
        }
    }

    @Test
    fun review_rejectsMalformedSignatureBase64() {
        val unsigned = unsignedManifest()
        val bad = JsonObject(unsigned + mapOf("signature" to JsonPrimitive("!!!not-base64!!!")))
        val raw = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), bad,
        ).toByteArray()
        assertThrows(ServiceManifestException.SignatureInvalid::class.java) {
            reviewer.review(raw, now = now)
        }
    }

    @Test
    fun review_rejectsWrongLengthSignature() {
        val unsigned = unsignedManifest()
        val bad = JsonObject(unsigned + mapOf("signature" to JsonPrimitive("c2hvcnQ=")))
        val raw = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), bad,
        ).toByteArray()
        assertThrows(ServiceManifestException.SignatureInvalid::class.java) {
            reviewer.review(raw, now = now)
        }
    }

    // ─── digest pin ───────────────────────────────────────────────

    @Test
    fun review_rejectsDigestMismatch() {
        val raw = defaultSignedManifest()
        val wrong = "sha256:" + "0".repeat(64)
        val error = assertThrows(ServiceManifestException.DigestMismatch::class.java) {
            reviewer.review(raw, expectedDigest = wrong, now = now)
        }
        assertEquals(wrong, error.expected)
        assertEquals(digestOf(raw), error.actual)
    }

    @Test
    fun review_rejectsMalformedDigestPin() {
        // A pin that isn't sha256:<64-lowercase-hex> can never match.
        val raw = defaultSignedManifest()
        assertThrows(ServiceManifestException.DigestMismatch::class.java) {
            reviewer.review(raw, expectedDigest = "SHA256:" + "0".repeat(64), now = now)
        }
    }

    // ─── expiry ───────────────────────────────────────────────────

    @Test
    fun review_rejectsExpiredManifest() {
        val raw = signedManifestBytes(unsignedManifest(validUntil = "2026-08-13T11:59:59Z"))
        val error = assertThrows(ServiceManifestException.Expired::class.java) {
            reviewer.review(raw, now = now)
        }
        assertEquals(Instant.parse("2026-08-13T11:59:59Z"), error.validUntil)
    }

    @Test
    fun review_acceptsValidUntilExactlyNow() {
        // iOS uses `validUntil < now` — the boundary instant is valid.
        val raw = signedManifestBytes(unsignedManifest(validUntil = "2026-08-13T12:00:00Z"))
        reviewer.review(raw, now = now)
    }

    // ─── spine failures propagate ─────────────────────────────────

    @Test
    fun review_rejectsSpineViolationsEvenWhenSigned() {
        val raw = signedManifestBytes(unsignedManifest(componentId = "not-a-component-id"))
        assertThrows(ServiceManifestException.ManifestInvalid::class.java) {
            reviewer.review(raw, now = now)
        }
    }
}
