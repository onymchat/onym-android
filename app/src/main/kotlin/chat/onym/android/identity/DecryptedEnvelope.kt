package chat.onym.android.identity

/**
 * Output of [InvitationEnvelopeDecrypter.decryptInvitationWithSender].
 * Bundles the inner plaintext with the Ed25519 pubkey that signed
 * the outer [SealedEnvelope] — receivers that need to authenticate
 * the sender (e.g. the admin-Ed25519 trust check on a
 * `MemberAnnouncementPayload`) read both at the same time without
 * re-decoding the envelope.
 *
 * [senderEd25519PublicKey] is `null` when the envelope shipped
 * without a `sender_ed25519_public_key` block. That's allowed by
 * the wire format (the signature block is optional), so callers
 * that require provenance MUST treat `null` as "no proof of sender"
 * and refuse to act on the plaintext as if it were authenticated.
 *
 * Mirrors `DecryptedEnvelope.swift` from onym-ios.
 */
data class DecryptedEnvelope(
    val plaintext: ByteArray,
    /** 32-byte raw Ed25519 pubkey, or `null` when the envelope
     *  didn't include a signature block. */
    val senderEd25519PublicKey: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is DecryptedEnvelope &&
            plaintext.contentEquals(other.plaintext) &&
            (senderEd25519PublicKey?.contentEquals(other.senderEd25519PublicKey)
                ?: (other.senderEd25519PublicKey == null)))

    override fun hashCode(): Int {
        var h = plaintext.contentHashCode()
        h = 31 * h + (senderEd25519PublicKey?.contentHashCode() ?: 0)
        return h
    }
}
