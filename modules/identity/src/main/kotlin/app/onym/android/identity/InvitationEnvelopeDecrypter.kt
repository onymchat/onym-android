package app.onym.android.identity

/**
 * Narrow decryption seam. The only producer is [IdentityRepository]
 * (see [IdentityRepository.decryptInvitation]) — secret material
 * (X25519 private key, nostr secret) never escapes the repository
 * actor; downstream interactors only ever see the plaintext bytes
 * this method returns.
 *
 * Lives in the identity layer alongside the only producer. The
 * inbox-side interactor ([app.onym.android.inbox.InvitationDecryptor])
 * holds a reference to this interface — *not* to `IdentityRepository`
 * directly — so tests substitute fakes.
 *
 * Mirrors `InvitationEnvelopeDecrypting.swift` from onym-ios PR #17.
 */
interface InvitationEnvelopeDecrypter {
    /**
     * Decrypt an X25519+AES-GCM-sealed invitation envelope using the
     * private key of the identity addressed by [asIdentity].
     *
     * The explicit [asIdentity] parameter (added in PR-6 of the
     * deeplink-invite stack, mirroring onym-ios PR #59) is what
     * makes the multi-identity inbox fan-out actually decrypt
     * correctly: an envelope addressed to identity B that arrived
     * while the user was on identity A still decrypts under B's
     * key when surfaced. The repository tags every persisted record
     * with its [asIdentity] at receive time
     * ([app.onym.android.inbox.IncomingInvitationsRepository.recordIncoming])
     * and the decrypter consumes that tag here.
     *
     * @param envelopeBytes UTF-8 JSON of a [SealedEnvelope] with
     *        `scheme = "x25519-aes-256-gcm-v1"`.
     * @param asIdentity the identity whose X25519 private key should
     *        be used to open the envelope. Pass the value the
     *        repository stamped on
     *        [app.onym.android.inbox.IncomingInvitation.ownerIdentityId].
     * @return the inner plaintext (typically a `BootstrapPayload`
     *         JSON for the inbox interactor to parse downstream).
     * @throws InvitationDecryptError on every failure mode — the
     *         seam never throws raw `javax.crypto` /
     *         `kotlinx.serialization` exceptions, so callers can
     *         classify with a `when (e: InvitationDecryptError)`.
     */
    suspend fun decryptInvitation(envelopeBytes: ByteArray, asIdentity: IdentityId): ByteArray

    /**
     * Same as [decryptInvitation] but also surfaces the sealed
     * envelope's `sender_ed25519_public_key` block so callers that
     * need provenance can authenticate the sender without re-parsing.
     *
     * The receive-side dispatcher (PR 80) reads both at the same
     * hop: the plaintext routes to the right fast-path decoder, the
     * sender pubkey gates `MemberAnnouncementPayload` against the
     * group's stored admin Ed25519 (PR 84).
     *
     * `senderEd25519PublicKey == null` is allowed by the wire format
     * — callers that require authenticated provenance MUST refuse
     * to act on the plaintext when it's null.
     */
    suspend fun decryptInvitationWithSender(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): DecryptedEnvelope
}

/**
 * Failure modes for [InvitationEnvelopeDecrypter.decryptInvitation].
 * Sealed so `when` exhaustiveness checks downstream don't need an
 * `else` branch.
 */
sealed class InvitationDecryptError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** JSON parse failed — the envelope isn't well-formed. */
    class MalformedEnvelope(reason: String, cause: Throwable? = null) : InvitationDecryptError(reason, cause)

    /** Scheme tag wasn't `"x25519-aes-256-gcm-v1"`. */
    class UnsupportedScheme(val scheme: String) : InvitationDecryptError("unsupported scheme: $scheme")

    /** Envelope omitted `ephemeral_public_key` (required for ECDH). */
    object MissingEphemeralKey : InvitationDecryptError("envelope missing ephemeral_public_key") {
        private fun readResolve(): Any = MissingEphemeralKey
    }

    /** M-5 Ed25519 signature over the ephemeral pubkey is present
     *  but doesn't verify (tampered key, wrong sender pubkey, …). */
    object SignatureFailed : InvitationDecryptError("ephemeral key signature verification failed") {
        private fun readResolve(): Any = SignatureFailed
    }

    /** AES-GCM auth tag check failed. Covers both "ciphertext was
     *  tampered with" and "envelope was sent to a different
     *  recipient" — semantically indistinguishable at this layer. */
    class DecryptionFailed(cause: Throwable) :
        InvitationDecryptError("decryption failed (auth tag check)", cause)

    /** [IdentityRepository.bootstrap] hasn't been called or [wipe] was
     *  called — there's no recipient secret to derive the X25519 key
     *  from. */
    object IdentityNotLoaded : InvitationDecryptError("identity not loaded; call bootstrap() first") {
        private fun readResolve(): Any = IdentityNotLoaded
    }
}
