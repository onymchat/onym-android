package app.onym.android.push

import app.onym.android.foundation.Bip39
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The envelope from the server's side of the table: a fixed X25519
 * private key plays the backend, opens what [PushTokenEnvelope.seal]
 * produced, and must read the exact token — the construction the
 * Rust backend implements against the same salt and info strings.
 */
class PushTokenEnvelopeTest {
    // Fixed, not random: the "server" key is a stand-in for the
    // backend's stored private half.
    private val serverPrivate = X25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
    private val serverPublic = serverPrivate.generatePublicKey().encoded
    private val token = "fcm-registration-token-fixture"

    private fun open(envelope: PushTokenEnvelope, private: X25519PrivateKeyParameters): ByteArray {
        val shared = ByteArray(32)
        X25519Agreement().apply { init(private) }.calculateAgreement(
            X25519PublicKeyParameters(envelope.ephemeralPublicKey, 0),
            shared,
            0,
        )
        val key = Bip39.hkdfSha256(
            ikm = shared,
            salt = "onym-push-token-v1".toByteArray(Charsets.UTF_8),
            info = "aes-256-gcm".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, envelope.nonce),
        )
        return cipher.doFinal(envelope.ciphertext + envelope.authenticationTag)
    }

    @Test
    fun `the server's private key opens the sealed token`() {
        val envelope = PushTokenEnvelope.seal(token, serverPublic)
        assertEquals(token, open(envelope, serverPrivate).decodeToString())
    }

    @Test
    fun `a different private key cannot open it`() {
        val envelope = PushTokenEnvelope.seal(token, serverPublic)
        val wrong = X25519PrivateKeyParameters(SecureRandom())
        try {
            open(envelope, wrong)
            fail("a wrong key must fail the AEAD open")
        } catch (_: Exception) {
            // AEADBadTagException (or a provider-specific wrapper) —
            // any failure is the point.
        }
    }

    @Test
    fun `the shapes are 32-12-16`() {
        val envelope = PushTokenEnvelope.seal(token, serverPublic)
        assertEquals(32, envelope.ephemeralPublicKey.size)
        assertEquals(12, envelope.nonce.size)
        assertEquals(16, envelope.authenticationTag.size)
        assertEquals(token.encodeToByteArray().size, envelope.ciphertext.size)
    }

    /** Fresh ephemeral key AND fresh nonce per seal — two envelopes
     * of the same token must share no randomness. */
    @Test
    fun `every seal draws fresh randomness`() {
        val first = PushTokenEnvelope.seal(token, serverPublic)
        val second = PushTokenEnvelope.seal(token, serverPublic)
        assertFalse(first.ephemeralPublicKey.contentEquals(second.ephemeralPublicKey))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    /** camelCase, base64 fields — the wire form the register body
     * embeds. */
    @Test
    fun `serializes to camelCase base64 fields`() {
        val envelope = PushTokenEnvelope.seal(token, serverPublic)
        val json = PushJson.json.encodeToString(PushTokenEnvelope.serializer(), envelope)
        assertTrue(json.contains("\"ephemeralPublicKey\""))
        assertTrue(json.contains("\"nonce\""))
        assertTrue(json.contains("\"ciphertext\""))
        assertTrue(json.contains("\"authenticationTag\""))
        val decoded = PushJson.json.decodeFromString(PushTokenEnvelope.serializer(), json)
        assertEquals(envelope, decoded)
    }
}
