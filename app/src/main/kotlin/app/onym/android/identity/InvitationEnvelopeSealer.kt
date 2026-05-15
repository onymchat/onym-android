package app.onym.android.identity

/**
 * Sender-side mirror of [InvitationEnvelopeDecrypter]. The chain
 * interactor that creates a group depends on this seam to wrap each
 * invitee's bootstrap payload in an X25519+AES-GCM-sealed envelope —
 * without ever holding the device's long-term identity keys directly.
 *
 * Only the producer of this interface (i.e. [IdentityRepository])
 * holds the Ed25519 signing key used to attest the per-envelope
 * ephemeral X25519 pubkey (M-5). The interactor only sees the
 * resulting bytes ready to drop on `InboxTransport.send(...)`.
 *
 * Mirrors `InvitationEnvelopeSealing.swift` from onym-ios PR #24.
 */
interface InvitationEnvelopeSealer {
    /**
     * Seal [payload] for a single recipient. Generates a fresh
     * per-envelope X25519 keypair, derives the AES-GCM key via
     * HKDF-SHA256 over the ECDH shared secret with
     * [recipientInboxPublicKey] (32-byte raw X25519 pubkey), encrypts
     * the payload with a random 12-byte nonce, signs the ephemeral
     * pubkey with the sender's Ed25519 identity key (M-5), and
     * returns the JSON-encoded [SealedEnvelope] bytes.
     *
     * Throws [InvitationSealError]; never raw `javax.crypto` /
     * `kotlinx.serialization` exceptions, so callers can classify
     * with a `when (e: InvitationSealError)`.
     */
    suspend fun sealInvitation(payload: ByteArray, recipientInboxPublicKey: ByteArray): ByteArray
}

/**
 * Failure modes for [InvitationEnvelopeSealer.sealInvitation].
 * Sealed so `when` exhaustiveness checks downstream don't need an
 * `else` branch.
 */
sealed class InvitationSealError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** [IdentityRepository.bootstrap] hasn't been called or [wipe]
     *  was called — there's no sender secret to derive the M-5
     *  signing key from. */
    object IdentityNotLoaded : InvitationSealError("identity not loaded; call bootstrap() first") {
        private fun readResolve(): Any = IdentityNotLoaded
    }

    /** Recipient's public key wasn't a 32-byte X25519 raw pubkey. */
    class InvalidRecipientPublicKey(reason: String) :
        InvitationSealError("invalid recipient X25519 public key: $reason")

    /** Ed25519 sign over the ephemeral pubkey failed. */
    class SigningFailed(cause: Throwable) :
        InvitationSealError("ephemeral key signing failed", cause)

    /** AES-GCM seal failed (key derivation or cipher init). */
    class EncryptionFailed(cause: Throwable) :
        InvitationSealError("AES-GCM seal failed", cause)

    /** Encoding the resulting [SealedEnvelope] to JSON failed. */
    class EncodingFailed(cause: Throwable) :
        InvitationSealError("envelope JSON encode failed", cause)
}
