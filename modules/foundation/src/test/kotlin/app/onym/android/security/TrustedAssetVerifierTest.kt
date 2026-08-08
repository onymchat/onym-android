package app.onym.android.security

import app.onym.android.foundation.SignatureVerificationException
import app.onym.android.foundation.TrustedAssetVerifier
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * H-3 unit tests for the shared Ed25519 detached-signature verifier.
 * Uses BouncyCastle directly to mint a throwaway keypair + real
 * signature — the same primitives the production verifier uses.
 */
class TrustedAssetVerifierTest {

    private val asset = "the trust-critical asset bytes".toByteArray(Charsets.UTF_8)

    private data class KeyPair(val publicKey: ByteArray, val sign: (ByteArray) -> ByteArray)

    private fun freshKey(): KeyPair {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey().encoded
        return KeyPair(pub) { message ->
            Ed25519Signer().apply {
                init(true, priv)
                update(message, 0, message.size)
            }.generateSignature()
        }
    }

    @Test
    fun verify_validSignature_verifies() {
        val key = freshKey()
        val verifier = TrustedAssetVerifier(publicKey = key.publicKey, enforce = false)
        assertEquals(TrustedAssetVerifier.Result.VERIFIED, verifier.verify(asset, key.sign(asset)))
    }

    @Test
    fun verify_tamperedBytes_fails() {
        val key = freshKey()
        val sig = key.sign(asset)
        val tampered = asset.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val verifier = TrustedAssetVerifier(publicKey = key.publicKey, enforce = false)
        assertEquals(TrustedAssetVerifier.Result.INVALID, verifier.verify(tampered, sig))
    }

    @Test
    fun verify_wrongKey_fails() {
        val signingKey = freshKey()
        val otherKey = freshKey()
        val sig = signingKey.sign(asset)
        val verifier = TrustedAssetVerifier(publicKey = otherKey.publicKey, enforce = false)
        assertEquals(TrustedAssetVerifier.Result.INVALID, verifier.verify(asset, sig))
    }

    @Test
    fun verify_missingSignature_reportsMissing() {
        val verifier = TrustedAssetVerifier(publicKey = freshKey().publicKey, enforce = false)
        assertEquals(TrustedAssetVerifier.Result.MISSING, verifier.verify(asset, null))
    }

    @Test
    fun verify_malformedSignatureLength_isInvalidNotCrash() {
        val verifier = TrustedAssetVerifier(publicKey = freshKey().publicKey, enforce = false)
        assertEquals(TrustedAssetVerifier.Result.INVALID, verifier.verify(asset, ByteArray(10)))
    }

    @Test
    fun gate_softMode_missingSignature_proceedsAndWarns() {
        var warned: String? = null
        val verifier = TrustedAssetVerifier(
            publicKey = freshKey().publicKey,
            enforce = false,
            logWarning = { warned = it },
        )
        // Does not throw.
        verifier.gate("asset.json", asset, null)
        assertTrue("soft mode must log a warning", warned != null)
    }

    @Test
    fun gate_softMode_invalidSignature_proceedsAndWarns() {
        val key = freshKey()
        var warned: String? = null
        val verifier = TrustedAssetVerifier(
            publicKey = freshKey().publicKey, // wrong key -> invalid
            enforce = false,
            logWarning = { warned = it },
        )
        verifier.gate("asset.json", asset, key.sign(asset))
        assertTrue(warned != null)
    }

    @Test
    fun gate_enforceMode_missingSignature_throws() {
        val verifier = TrustedAssetVerifier(publicKey = freshKey().publicKey, enforce = true)
        assertThrows(SignatureVerificationException::class.java) {
            verifier.gate("asset.json", asset, null)
        }
    }

    @Test
    fun gate_enforceMode_invalidSignature_throws() {
        val key = freshKey()
        val verifier = TrustedAssetVerifier(publicKey = freshKey().publicKey, enforce = true)
        assertThrows(SignatureVerificationException::class.java) {
            verifier.gate("asset.json", asset, key.sign(asset))
        }
    }

    @Test
    fun gate_enforceMode_validSignature_proceeds() {
        val key = freshKey()
        val verifier = TrustedAssetVerifier(publicKey = key.publicKey, enforce = true)
        // Does not throw.
        verifier.gate("asset.json", asset, key.sign(asset))
    }

    @Test
    fun defaults_areSoftModeWithPlaceholderKey() {
        // Guards the shipped configuration: enforcement OFF, key is the
        // 32-zero-byte placeholder until the real offline key is bundled.
        assertEquals(false, TrustedAssetVerifier.ENFORCE_SIGNATURES)
        assertEquals(32, TrustedAssetVerifier.SIGNING_PUBLIC_KEY.size)
        assertTrue(TrustedAssetVerifier.SIGNING_PUBLIC_KEY.all { it == 0.toByte() })
    }
}
