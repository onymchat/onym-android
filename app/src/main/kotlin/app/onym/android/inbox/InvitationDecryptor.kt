package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import app.onym.android.identity.InvitationEnvelopeDecrypter
import kotlinx.serialization.json.Json

/**
 * Stateless interactor that pumps an encrypted invitation envelope
 * through the seam decrypter and parses the inner plaintext into a
 * [DecryptedInvitation].
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** an [InvitationEnvelopeDecrypter] reference. No
 *    other repository, no OnymSDK, no `Context`.
 *  - The seam strips secret material on its side; this interactor
 *    only ever sees plaintext bytes.
 *  - Stateless — the only mutable thing in scope is the [Json]
 *    parser, which is thread-safe + immutable after construction.
 *
 * Mirrors `InvitationDecryptor.swift` from onym-ios PR #17 + the
 * per-identity routing arg from onym-ios PR #59.
 */
class InvitationDecryptor(
    private val envelopeDecrypter: InvitationEnvelopeDecrypter,
) {
    /**
     * Open + parse [envelopeBytes] (UTF-8 JSON of a
     * [app.onym.android.identity.SealedEnvelope]) using the X25519
     * key of the identity addressed by [asIdentity].
     *
     * Pass the value the repository stamped on
     * [IncomingInvitation.ownerIdentityId]. Hard-coding the
     * currently-selected identity here would silently fail to
     * decrypt envelopes addressed to a non-active identity — the
     * whole reason PR-6 of the deeplink-invite stack exists.
     *
     * @throws app.onym.android.identity.InvitationDecryptError on
     *         decryption failure; propagated verbatim from the seam.
     * @throws kotlinx.serialization.SerializationException if the
     *         decrypted plaintext isn't a well-formed
     *         [DecryptedInvitation] JSON.
     */
    suspend fun decrypt(envelopeBytes: ByteArray, asIdentity: IdentityId): DecryptedInvitation {
        val plaintext = envelopeDecrypter.decryptInvitation(envelopeBytes, asIdentity)
        return jsonFormat.decodeFromString(plaintext.toString(Charsets.UTF_8))
    }

    private companion object {
        // Permissive — extra fields the sender added in a future
        // BootstrapPayload version (members, salt, relayHints, …)
        // are silently dropped, so this 4-field subset stays valid.
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
