package app.onym.android.foundation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ephemeral X25519 + ECDH + HKDF-SHA256 + AES-GCM + Ed25519 sealing —
 * the same construction shape this codebase already uses for
 * invitation envelopes (`:identity`'s `InvitationEnvelopeSealer`), but
 * with its own scheme string and HKDF salt, deliberately NOT shared
 * with the invitation suite (domain separation: a seat-entitlement
 * envelope and an invitation envelope must never be mistaken for one
 * another even if a key were ever reused).
 *
 * `:foundation` cannot depend on `:identity`, so this is a standalone
 * implementation rather than a reuse of `InvitationEnvelopeSealer`'s
 * (identity-key-bound) private sealing code — same *shape*, distinct
 * *keys and constants* by design.
 *
 * Mirrors `SeatSealedEnvelope` in onym-ios OnymBilling.
 */
class SeatSealedEnvelope private constructor(
    val ephemeralPublicKey: ByteArray,
    val ephemeralKeySignature: ByteArray,
    val senderEd25519PublicKey: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val authenticationTag: ByteArray,
) {
    fun toJson(): String {
        val obj = JsonObject(
            mapOf(
                "scheme" to JsonPrimitive(SCHEME),
                "ephemeral_public_key" to JsonPrimitive(Base64.getEncoder().encodeToString(ephemeralPublicKey)),
                "ephemeral_key_signature" to JsonPrimitive(Base64.getEncoder().encodeToString(ephemeralKeySignature)),
                "sender_ed25519_public_key" to JsonPrimitive(Base64.getEncoder().encodeToString(senderEd25519PublicKey)),
                "nonce" to JsonPrimitive(Base64.getEncoder().encodeToString(nonce)),
                "ciphertext" to JsonPrimitive(Base64.getEncoder().encodeToString(ciphertext)),
                "authentication_tag" to JsonPrimitive(Base64.getEncoder().encodeToString(authenticationTag)),
            ),
        )
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        const val SCHEME = "x25519-aes-256-gcm-seat-v1"
        private val HKDF_SALT = "onym-seat-entitlement-v1".toByteArray(Charsets.UTF_8)
        private val HKDF_INFO = ByteArray(0)
        private const val TAG_BYTES = 16
        private const val NONCE_BYTES = 12

        fun seal(
            payload: ByteArray,
            recipientAgreementPublicKey: ByteArray,
            senderSigningKey: Ed25519PrivateKeyParameters,
        ): SeatSealedEnvelope {
            val ephemeral = X25519PrivateKeyParameters(SecureRandom())
            val ephemeralPublic = ephemeral.generatePublicKey().encoded

            val sharedSecret = ByteArray(32)
            X25519Agreement().apply { init(ephemeral) }
                .calculateAgreement(X25519PublicKeyParameters(recipientAgreementPublicKey, 0), sharedSecret, 0)
            val aesKey = Bip39.hkdfSha256(sharedSecret, HKDF_SALT, HKDF_INFO, 32)

            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            val combined = cipher.doFinal(payload)
            val ciphertext = combined.copyOfRange(0, combined.size - TAG_BYTES)
            val tag = combined.copyOfRange(combined.size - TAG_BYTES, combined.size)

            val signature = Ed25519Signer().apply {
                init(true, senderSigningKey)
                update(ephemeralPublic, 0, ephemeralPublic.size)
            }.generateSignature()

            return SeatSealedEnvelope(
                ephemeralPublicKey = ephemeralPublic,
                ephemeralKeySignature = signature,
                senderEd25519PublicKey = senderSigningKey.generatePublicKey().encoded,
                nonce = nonce,
                ciphertext = ciphertext,
                authenticationTag = tag,
            )
        }

        fun fromJson(json: String): SeatSealedEnvelope {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (_: Exception) {
                throw IllegalArgumentException("malformed seat-sealed envelope JSON")
            }
            fun bytes(key: String): ByteArray {
                val value = (obj[key] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("missing $key")
                return try {
                    Base64.getDecoder().decode(value)
                } catch (_: Exception) {
                    throw IllegalArgumentException("$key is not valid base64")
                }
            }
            return SeatSealedEnvelope(
                ephemeralPublicKey = bytes("ephemeral_public_key"),
                ephemeralKeySignature = bytes("ephemeral_key_signature"),
                senderEd25519PublicKey = bytes("sender_ed25519_public_key"),
                nonce = bytes("nonce"),
                ciphertext = bytes("ciphertext"),
                authenticationTag = bytes("authentication_tag"),
            )
        }

        /**
         * Opens [envelope] with [recipientAgreementPrivateKey].
         * [expectedSenders] must be non-empty — an empty set is
         * refused outright rather than treated as "accept anyone."
         *
         * @throws IllegalArgumentException on an empty [expectedSenders],
         *   an untrusted sender, a bad ephemeral-key signature, or a
         *   failed AEAD open.
         */
        fun open(
            envelope: SeatSealedEnvelope,
            recipientAgreementPrivateKey: X25519PrivateKeyParameters,
            expectedSenders: Set<Ed25519PublicKeyParameters>,
        ): ByteArray {
            require(expectedSenders.isNotEmpty()) { "expectedSenders must not be empty" }

            val sender = Ed25519PublicKeyParameters(envelope.senderEd25519PublicKey, 0)
            require(expectedSenders.any { it.encoded.contentEquals(sender.encoded) }) {
                "sender is not in the trusted set"
            }

            val signatureValid = Ed25519Signer().apply {
                init(false, sender)
                update(envelope.ephemeralPublicKey, 0, envelope.ephemeralPublicKey.size)
            }.verifySignature(envelope.ephemeralKeySignature)
            require(signatureValid) { "ephemeral key signature does not verify" }

            val sharedSecret = ByteArray(32)
            X25519Agreement().apply { init(recipientAgreementPrivateKey) }
                .calculateAgreement(X25519PublicKeyParameters(envelope.ephemeralPublicKey, 0), sharedSecret, 0)
            val aesKey = Bip39.hkdfSha256(sharedSecret, HKDF_SALT, HKDF_INFO, 32)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, envelope.nonce),
            )
            return cipher.doFinal(envelope.ciphertext + envelope.authenticationTag)
        }
    }
}
