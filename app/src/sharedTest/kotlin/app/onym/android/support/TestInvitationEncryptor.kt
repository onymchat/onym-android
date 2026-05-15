package app.onym.android.support

import app.onym.android.identity.Bip39
import app.onym.android.identity.SealedEnvelope
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only sender-side helper. Replicates the
 * [stellar-mls/clients/android/StellarChat/.../crypto/GroupCrypto.kt
 * encryptInvitation](https://github.com/stellarmls/clients-android)
 * formula so round-trip tests can produce real
 * `x25519-aes-256-gcm-v1` ciphertext without polluting production
 * with sender-side code (no app code sends invitations yet — that's
 * a future capability).
 *
 * Will move to production when the app gains create-group +
 * invite-contact capability; until then it lives in
 * `app/src/test/.../support/` so the production target carries
 * zero invitation-sender code.
 *
 * Mirrors `TestInvitationEncryptor.swift` from onym-ios PR #17.
 */
object TestInvitationEncryptor {

    /**
     * Build a sealed envelope addressed to [recipientX25519Pubkey].
     * If [senderEd25519Private] is supplied, signs the ephemeral
     * pubkey with it (M-5 path) and includes the sender's Ed25519
     * pubkey in the envelope so the recipient can verify without an
     * out-of-band key exchange.
     *
     * @param payload arbitrary plaintext (typically a serialized
     *        BootstrapPayload subset; tests pass anything).
     */
    fun sealedEnvelope(
        payload: ByteArray,
        recipientX25519Pubkey: ByteArray,
        senderEd25519Private: Ed25519PrivateKeyParameters? = null,
    ): SealedEnvelope {
        // Ephemeral X25519 keypair — fresh per call.
        val ephemeralPrivate = X25519PrivateKeyParameters(SecureRandom())
        val ephemeralPublic = ephemeralPrivate.generatePublicKey()

        // ECDH against the recipient.
        val recipientParams = X25519PublicKeyParameters(recipientX25519Pubkey, 0)
        val agreement = X25519Agreement().apply { init(ephemeralPrivate) }
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(recipientParams, sharedSecret, 0)

        // HKDF — same constants the production decrypter uses.
        val aesKey = Bip39.hkdfSha256(
            ikm = sharedSecret,
            salt = "sep-invitation-v1".toByteArray(Charsets.UTF_8),
            info = "aes-256-gcm".toByteArray(Charsets.UTF_8),
            length = 32,
        )

        // AES-GCM encrypt — split the trailing 16-byte tag off the
        // ciphertext to match the JSON envelope shape.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        }
        val nonce = cipher.iv
        val ciphertextWithTag = cipher.doFinal(payload)
        val tagOffset = ciphertextWithTag.size - 16
        val ciphertext = ciphertextWithTag.copyOfRange(0, tagOffset)
        val tag = ciphertextWithTag.copyOfRange(tagOffset, ciphertextWithTag.size)

        // M-5: optionally sign the ephemeral pubkey.
        val (sig, senderPub) = if (senderEd25519Private != null) {
            val signer = Ed25519Signer().apply { init(true, senderEd25519Private) }
            signer.update(ephemeralPublic.encoded, 0, ephemeralPublic.encoded.size)
            signer.generateSignature() to senderEd25519Private.generatePublicKey().encoded
        } else null to null

        return SealedEnvelope(
            version = 1,
            scheme = "x25519-aes-256-gcm-v1",
            ephemeralPublicKey = ephemeralPublic.encoded,
            ephemeralKeySignature = sig,
            senderEd25519PublicKey = senderPub,
            nonce = nonce,
            ciphertext = ciphertext,
            authenticationTag = tag,
        )
    }

    /** Same as [sealedEnvelope] but returns the JSON-encoded UTF-8
     *  bytes — drop straight into
     *  [app.onym.android.identity.InvitationEnvelopeDecrypter.decryptInvitation]. */
    fun envelopeBytes(
        payload: ByteArray,
        recipientX25519Pubkey: ByteArray,
        senderEd25519Private: Ed25519PrivateKeyParameters? = null,
    ): ByteArray = jsonFormat.encodeToString(
        SealedEnvelope.serializer(),
        sealedEnvelope(payload, recipientX25519Pubkey, senderEd25519Private),
    ).toByteArray(Charsets.UTF_8)

    private val jsonFormat = Json { encodeDefaults = true }
}
