package app.onym.android.push

import app.onym.android.foundation.Base64ByteArraySerializer
import app.onym.android.foundation.X25519Sealed
import kotlinx.serialization.Serializable

/**
 * The FCM registration token, sealed to the push backend's published
 * X25519 key so only the backend's private half — never its TLS
 * terminator, logs, or storage layer at rest — can read it.
 *
 * Construction ([X25519Sealed] — the `SeatSealedEnvelope` core,
 * unsigned): ephemeral X25519 → shared secret with the server key →
 * HKDF-SHA256 (salt `onym-push-token-v1`, info `aes-256-gcm`, 32
 * bytes) → AES-256-GCM with a 12-byte random nonce, the 16-byte tag
 * split out.
 *
 * On the HKDF salt: `SeatSealedEnvelope`'s doctrine is that every
 * envelope purpose gets its own salt so two envelope kinds can never
 * be mistaken for one another. This envelope deliberately REUSES the
 * cross-platform push salt iOS already sealed under: it is the same
 * purpose — a push token sealed to a push backend's registration key
 * — and the domain separation that matters here is per-server-key
 * (each platform's backend publishes its own X25519 key) plus the
 * per-platform context strings in [SignedPushPayload]. A per-platform
 * salt would fork one purpose into two constructions for no
 * additional security.
 *
 * No sender signature field: the register request the envelope rides
 * in is already identity-signed over bytes that include the token
 * itself, so a separate ephemeral-key signature would bind nothing
 * the backend doesn't already verify.
 */
@Serializable
data class PushTokenEnvelope(
    @Serializable(with = Base64ByteArraySerializer::class)
    val ephemeralPublicKey: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val nonce: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val ciphertext: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val authenticationTag: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushTokenEnvelope) return false
        return ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            authenticationTag.contentEquals(other.authenticationTag)
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authenticationTag.contentHashCode()
        return result
    }

    companion object {
        internal val HKDF_SALT = "onym-push-token-v1".toByteArray(Charsets.UTF_8)
        internal val HKDF_INFO = "aes-256-gcm".toByteArray(Charsets.UTF_8)
        private const val X25519_KEY_BYTES = 32

        /**
         * Seals [fcmToken] (the UTF-8 bytes of the token string) to
         * [serverPublicKey], the backend's 32-byte X25519 registration
         * key fetched fresh from `GET /v1/registration-key`.
         *
         * @throws PushBackendUnreachableException when [serverPublicKey]
         *   is not 32 bytes — a malformed key from the backend is a bad
         *   *response*, classified retryable like any other one (a
         *   rotated-away or truncated key heals server-side), never an
         *   `ArrayIndexOutOfBoundsException` out of the crypto layer.
         */
        fun seal(fcmToken: String, serverPublicKey: ByteArray): PushTokenEnvelope {
            if (serverPublicKey.size != X25519_KEY_BYTES) {
                throw PushBackendUnreachableException(
                    "push backend registration key must be $X25519_KEY_BYTES bytes, " +
                        "got ${serverPublicKey.size}",
                )
            }
            val sealed = X25519Sealed.seal(
                payload = fcmToken.encodeToByteArray(),
                recipientPublicKey = serverPublicKey,
                salt = HKDF_SALT,
                info = HKDF_INFO,
            )
            return PushTokenEnvelope(
                ephemeralPublicKey = sealed.ephemeralPublicKey,
                nonce = sealed.nonce,
                ciphertext = sealed.ciphertext,
                authenticationTag = sealed.authenticationTag,
            )
        }
    }
}
