package app.onym.android.foundation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

class SeatEntitlementTest {

    private fun signedEntitlementBytes(
        issuerKey: Ed25519PrivateKeyParameters,
        audience: String = "onym:component:relay-1",
        subject: String = "onym:seat-key:" + "a".repeat(64),
        offerId: String = "relay-monthly-v1",
        notBefore: Instant = Instant.now().minusSeconds(60),
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        entitlementId: String = "entitlement-1",
    ): ByteArray {
        val issuerHex = issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
        val unsigned = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "type" to JsonPrimitive("SeatEntitlement"),
                "issuer" to JsonPrimitive("onym:key:$issuerHex"),
                "audience" to JsonPrimitive(audience),
                "subject" to JsonPrimitive(subject),
                "offerId" to JsonPrimitive(offerId),
                "entitlementId" to JsonPrimitive(entitlementId),
                "notBefore" to JsonPrimitive(BackupFormatCompatRfc3339(notBefore)),
                "expiresAt" to JsonPrimitive(BackupFormatCompatRfc3339(expiresAt)),
                "status" to JsonPrimitive("https://broker.example/v1/revocations"),
            ),
        )
        val unsignedBytes = Json.encodeToString(JsonObject.serializer(), unsigned).toByteArray(Charsets.UTF_8)
        val signingBytes = ServiceManifestCanonical.signingBytes(unsignedBytes, omitting = setOf("signature"))
        val signature = Ed25519Signer().apply {
            init(true, issuerKey)
            update(signingBytes, 0, signingBytes.size)
        }.generateSignature()
        val signed = JsonObject(unsigned.toMutableMap().apply {
            put("signature", JsonPrimitive(Base64.getEncoder().encodeToString(signature)))
        })
        return Json.encodeToString(JsonObject.serializer(), signed).toByteArray(Charsets.UTF_8)
    }

    private fun BackupFormatCompatRfc3339(instant: Instant): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    @Test
    fun verify_accepts_a_correctly_signed_entitlement() {
        val issuerKey = Ed25519PrivateKeyParameters(SecureRandom())
        val issuerRef = "onym:key:" + issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
        val entitlement = SeatEntitlement.decode(signedEntitlementBytes(issuerKey))

        SeatEntitlementVerifier.verify(
            entitlement = entitlement,
            trustedIssuers = setOf(issuerRef),
            issuerPublicKeyHex = { issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) } },
            audience = "onym:component:relay-1",
            subject = "onym:seat-key:" + "a".repeat(64),
            revokedEntitlementIds = emptySet(),
        )
    }

    @Test
    fun verify_rejects_untrusted_issuer() {
        val issuerKey = Ed25519PrivateKeyParameters(SecureRandom())
        val entitlement = SeatEntitlement.decode(signedEntitlementBytes(issuerKey))
        assertThrows(SeatEntitlementException.UntrustedIssuer::class.java) {
            SeatEntitlementVerifier.verify(
                entitlement = entitlement,
                trustedIssuers = setOf("onym:key:" + "0".repeat(64)),
                issuerPublicKeyHex = { null },
                audience = "onym:component:relay-1",
                subject = "onym:seat-key:" + "a".repeat(64),
                revokedEntitlementIds = emptySet(),
            )
        }
    }

    @Test
    fun verify_rejects_wrong_subject() {
        val issuerKey = Ed25519PrivateKeyParameters(SecureRandom())
        val issuerRef = "onym:key:" + issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
        val entitlement = SeatEntitlement.decode(signedEntitlementBytes(issuerKey))
        assertThrows(SeatEntitlementException.WrongSubject::class.java) {
            SeatEntitlementVerifier.verify(
                entitlement = entitlement,
                trustedIssuers = setOf(issuerRef),
                issuerPublicKeyHex = { issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) } },
                audience = "onym:component:relay-1",
                subject = "onym:seat-key:" + "b".repeat(64),
                revokedEntitlementIds = emptySet(),
            )
        }
    }

    @Test
    fun verify_rejects_revoked_entitlement() {
        val issuerKey = Ed25519PrivateKeyParameters(SecureRandom())
        val issuerRef = "onym:key:" + issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }
        val entitlement = SeatEntitlement.decode(signedEntitlementBytes(issuerKey, entitlementId = "revoked-1"))
        assertThrows(SeatEntitlementException.Revoked::class.java) {
            SeatEntitlementVerifier.verify(
                entitlement = entitlement,
                trustedIssuers = setOf(issuerRef),
                issuerPublicKeyHex = { issuerKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) } },
                audience = "onym:component:relay-1",
                subject = "onym:seat-key:" + "a".repeat(64),
                revokedEntitlementIds = setOf("revoked-1"),
            )
        }
    }

    @Test
    fun seat_sealed_envelope_round_trip() {
        val senderKey = Ed25519PrivateKeyParameters(SecureRandom())
        val recipientAgreementKey = X25519PrivateKeyParameters(SecureRandom())
        val payload = "sealed entitlement bytes".toByteArray(Charsets.UTF_8)

        val envelope = SeatSealedEnvelope.seal(
            payload,
            recipientAgreementKey.generatePublicKey().encoded,
            senderKey,
        )
        val json = envelope.toJson()
        val decoded = SeatSealedEnvelope.fromJson(json)

        val opened = SeatSealedEnvelope.open(
            decoded,
            recipientAgreementKey,
            setOf(senderKey.generatePublicKey()),
        )
        assertArrayEquals(payload, opened)
    }

    @Test
    fun seat_sealed_envelope_refuses_empty_expected_senders() {
        val senderKey = Ed25519PrivateKeyParameters(SecureRandom())
        val recipientAgreementKey = X25519PrivateKeyParameters(SecureRandom())
        val envelope = SeatSealedEnvelope.seal(
            "payload".toByteArray(),
            recipientAgreementKey.generatePublicKey().encoded,
            senderKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SeatSealedEnvelope.open(envelope, recipientAgreementKey, emptySet())
        }
    }
}
