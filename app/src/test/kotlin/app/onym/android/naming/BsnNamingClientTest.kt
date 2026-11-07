package app.onym.android.naming

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fixture produced by the deployed Node provider implementation with public test-only keys. */
class BsnNamingClientTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/naming/bsn-v1.json")).readText()).jsonObject
    private val now = fixture.number("now")
    private val subject = fixture.text("subject")
    private val policy = fixture.text("policy")
    private fun client(time: Long = now, key: String = fixture.text("issuer")) = BsnNamingClient(clock = { time }, trustKey = key)
    private fun response(key: String) = fixture.getValue(key).jsonObject
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    @Test fun nodeSignaturesAndCanonicalDigestInteroperate() {
        val name = client().verifyResolution(response("active"), subject, policy).single()
        assertEquals("Алиса 🌍", name.displayName)
        assertEquals(now + 60000, name.validUntil)
        assertEquals(fixture.text("digest"), BsnNamingClient.digest(name.record))
    }
    @Test fun unacceptedAndRevokedRecordsAreNotDisplayNames() {
        assertTrue(client().verifyResolution(response("offer"), subject, policy).isEmpty())
        assertTrue(client().verifyResolution(response("revoked"), subject, policy).isEmpty())
    }
    @Test fun responseExpiresAtSixtySeconds() {
        rejected { client(now + 60000).verifyResolution(response("active"), subject, policy) }
    }
    @Test fun futureResponseIsRejected() {
        rejected { client(now - 6000).verifyResolution(response("active"), subject, policy) }
    }
    @Test fun untrustedIssuerIsRejected() {
        rejected { client(key = subject.removePrefix("onym:key:")).verifyResolution(response("active"), subject, policy) }
    }
    @Test fun subjectAndPolicyMustMatch() {
        rejected { client().verifyResolution(response("active"), "onym:key:" + fixture.text("issuer"), policy) }
        rejected { client().verifyResolution(response("active"), subject, "sha256:wrong") }
    }
    @Test fun tamperedResponseIsRejected() {
        val changed = JsonObject(response("active") + ("query" to JsonPrimitive("other")))
        rejected { client().verifyResolution(changed, subject, policy) }
    }
    @Test fun providerCannotInventHolderAcceptance() {
        rejected { client().verifyResolution(response("noAcceptance"), subject, policy) }
        rejected { client().verifyResolution(response("wrongDigest"), subject, policy) }
    }
}
