package app.onym.android.foundation

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * The one anonymous-sender sealing primitive: ephemeral X25519 →
 * ECDH with the recipient key → HKDF-SHA256 (caller's salt/info,
 * 32-byte key) → AES-256-GCM with a 12-byte random nonce and the
 * 16-byte tag split out.
 *
 * Domain separation lives entirely in the HKDF salt/info the CALLER
 * supplies — every envelope purpose brings its own constants, so two
 * envelope kinds can never be mistaken for one another even though
 * they share this construction. Signature layers (sender
 * authentication) are deliberately NOT here: [SeatSealedEnvelope]
 * signs the ephemeral key with the sender's Ed25519 key on top of
 * this, `:push`'s token envelope sends it unsigned because the
 * request it rides in is already identity-signed. One construction,
 * per-purpose framing.
 */
object X25519Sealed {

    /** The four sealed parts, before any purpose-specific framing. */
    class Sealed(
        val ephemeralPublicKey: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val authenticationTag: ByteArray,
    )

    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    fun seal(
        payload: ByteArray,
        recipientPublicKey: ByteArray,
        salt: ByteArray,
        info: ByteArray,
    ): Sealed {
        val ephemeral = X25519PrivateKeyParameters(SecureRandom())
        val ephemeralPublic = ephemeral.generatePublicKey().encoded

        val sharedSecret = ByteArray(32)
        X25519Agreement().apply { init(ephemeral) }
            .calculateAgreement(X25519PublicKeyParameters(recipientPublicKey, 0), sharedSecret, 0)
        val aesKey = Bip39.hkdfSha256(sharedSecret, salt, info, 32)

        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        val combined = cipher.doFinal(payload)
        return Sealed(
            ephemeralPublicKey = ephemeralPublic,
            nonce = nonce,
            ciphertext = combined.copyOfRange(0, combined.size - TAG_BYTES),
            authenticationTag = combined.copyOfRange(combined.size - TAG_BYTES, combined.size),
        )
    }

    /** The inverse of [seal] under the same salt/info.
     * @throws javax.crypto.AEADBadTagException (wrapped by the JCA as
     *   a `GeneralSecurityException`) when the key or data is wrong. */
    fun open(
        ephemeralPublicKey: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        authenticationTag: ByteArray,
        recipientPrivateKey: X25519PrivateKeyParameters,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray {
        val sharedSecret = ByteArray(32)
        X25519Agreement().apply { init(recipientPrivateKey) }
            .calculateAgreement(X25519PublicKeyParameters(ephemeralPublicKey, 0), sharedSecret, 0)
        val aesKey = Bip39.hkdfSha256(sharedSecret, salt, info, 32)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, nonce),
        )
        return cipher.doFinal(ciphertext + authenticationTag)
    }
}
