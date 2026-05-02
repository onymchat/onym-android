package chat.onym.android.inbox

import chat.onym.android.identity.InvitationEnvelopeDecrypter
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
 * Mirrors `InvitationDecryptor.swift` from onym-ios PR #17.
 */
class InvitationDecryptor(
    private val envelopeDecrypter: InvitationEnvelopeDecrypter,
) {
    /**
     * Open + parse [envelopeBytes] (UTF-8 JSON of a
     * [chat.onym.android.identity.SealedEnvelope]).
     *
     * @throws chat.onym.android.identity.InvitationDecryptError on
     *         decryption failure; propagated verbatim from the seam.
     * @throws kotlinx.serialization.SerializationException if the
     *         decrypted plaintext isn't a well-formed
     *         [DecryptedInvitation] JSON.
     */
    suspend fun decrypt(envelopeBytes: ByteArray): DecryptedInvitation {
        val plaintext = envelopeDecrypter.decryptInvitation(envelopeBytes)
        return jsonFormat.decodeFromString(plaintext.toString(Charsets.UTF_8))
    }

    private companion object {
        // Permissive — extra fields the sender added in a future
        // BootstrapPayload version (members, salt, relayHints, …)
        // are silently dropped, so this 4-field subset stays valid.
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
