package app.onym.android.identity

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * C-1 regression: the sealed-envelope decrypt path must surface the
 * sender's Ed25519 pubkey as authenticated provenance ONLY when the
 * envelope carried a signature over the ephemeral key AND that
 * signature verifies. An envelope that ships a
 * `sender_ed25519_public_key` block but NO `ephemeral_key_signature`
 * is an unauthenticated claim — an attacker who knows the recipient's
 * (public) inbox key can seal a perfectly decryptable envelope and
 * stamp any pubkey they like into it. Surfacing that pubkey would let
 * the receive-side dispatcher mis-attribute chat messages and pass the
 * admin/member trust gate on group rename / avatar / announcement.
 *
 * These are pure-JVM unit tests: they build a real X25519+AES-GCM
 * envelope with the exact production HKDF constants and drive the
 * companion crypto core directly. No Android runtime needed.
 */
class EnvelopeSenderAuthenticationTest {

    private val rng = SecureRandom()

    @Test
    fun unsignedEnvelope_withSenderBlock_isSurfacedAsUnauthenticated() {
        val recipient = X25519PrivateKeyParameters(rng)
        val payload = "hello".toByteArray(Charsets.UTF_8)
        // Attacker stamps a victim admin's pubkey but ships NO signature.
        val forgedSenderPubkey = ByteArray(32) { 0x10 }

        val json = sealEnvelope(
            payload = payload,
            recipientPublic = recipient.generatePublicKey(),
            senderEd25519PublicKey = forgedSenderPubkey,
            ephemeralKeySignature = null,
        )

        val decrypted = IdentityRepository.decryptSealedEnvelopeWithKeyAndSender(
            envelopeBytes = json,
            recipientX25519PrivateKey = recipient.encoded,
        )

        assertArrayEquals(payload, decrypted.plaintext)
        assertNull(
            "an unsigned sender_ed25519_public_key must NOT be surfaced as authenticated provenance",
            decrypted.senderEd25519PublicKey,
        )
    }

    @Test
    fun unsignedEnvelope_withoutSenderBlock_hasNullSender() {
        val recipient = X25519PrivateKeyParameters(rng)
        val payload = "hi".toByteArray(Charsets.UTF_8)

        val json = sealEnvelope(
            payload = payload,
            recipientPublic = recipient.generatePublicKey(),
            senderEd25519PublicKey = null,
            ephemeralKeySignature = null,
        )

        val decrypted = IdentityRepository.decryptSealedEnvelopeWithKeyAndSender(
            envelopeBytes = json,
            recipientX25519PrivateKey = recipient.encoded,
        )

        assertArrayEquals(payload, decrypted.plaintext)
        assertNull(decrypted.senderEd25519PublicKey)
    }

    @Test
    fun signedEnvelope_surfacesVerifiedSenderPubkey() {
        val recipient = X25519PrivateKeyParameters(rng)
        val payload = "signed".toByteArray(Charsets.UTF_8)
        val senderSigning = Ed25519PrivateKeyParameters(rng)
        val senderPubkey = senderSigning.generatePublicKey().encoded

        val json = sealEnvelope(
            payload = payload,
            recipientPublic = recipient.generatePublicKey(),
            senderEd25519PublicKey = senderPubkey,
            signWith = senderSigning,
        )

        val decrypted = IdentityRepository.decryptSealedEnvelopeWithKeyAndSender(
            envelopeBytes = json,
            recipientX25519PrivateKey = recipient.encoded,
        )

        assertArrayEquals(payload, decrypted.plaintext)
        assertArrayEquals(
            "a present-and-valid signature must surface the signer pubkey",
            senderPubkey,
            decrypted.senderEd25519PublicKey,
        )
    }

    // ─── helpers ──────────────────────────────────────────────────

    /**
     * Build a wire-shaped [SealedEnvelope] JSON matching the exact
     * production crypto (ECDH → HKDF-SHA256 "sep-invitation-v1" /
     * "aes-256-gcm" → AES-256-GCM). When [signWith] is provided the
     * ephemeral pubkey is Ed25519-signed; otherwise pass an explicit
     * [ephemeralKeySignature] (typically null) to model an unsigned
     * envelope.
     */
    private fun sealEnvelope(
        payload: ByteArray,
        recipientPublic: X25519PublicKeyParameters,
        senderEd25519PublicKey: ByteArray?,
        signWith: Ed25519PrivateKeyParameters? = null,
        ephemeralKeySignature: ByteArray? = null,
    ): ByteArray {
        val ephemeralPrivate = X25519PrivateKeyParameters(rng)
        val ephemeralPub = ephemeralPrivate.generatePublicKey().encoded

        val agreement = X25519Agreement().apply { init(ephemeralPrivate) }
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(recipientPublic, sharedSecret, 0)

        val aesKey = Bip39.hkdfSha256(
            ikm = sharedSecret,
            salt = "sep-invitation-v1".toByteArray(Charsets.UTF_8),
            info = "aes-256-gcm".toByteArray(Charsets.UTF_8),
            length = 32,
        )

        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        }
        val combined = cipher.doFinal(payload)
        val tagLen = 16
        val ciphertext = combined.copyOfRange(0, combined.size - tagLen)
        val authTag = combined.copyOfRange(combined.size - tagLen, combined.size)

        val signature = ephemeralKeySignature ?: signWith?.let { key ->
            Ed25519Signer().apply {
                init(true, key)
                update(ephemeralPub, 0, ephemeralPub.size)
            }.generateSignature()
        }

        val envelope = SealedEnvelope(
            version = 1,
            scheme = "x25519-aes-256-gcm-v1",
            ephemeralPublicKey = ephemeralPub,
            ephemeralKeySignature = signature,
            senderEd25519PublicKey = senderEd25519PublicKey,
            nonce = nonce,
            ciphertext = ciphertext,
            authenticationTag = authTag,
        )
        return IdentityRepository.envelopeJsonFormat
            .encodeToString(SealedEnvelope.serializer(), envelope)
            .toByteArray(Charsets.UTF_8)
    }
}
