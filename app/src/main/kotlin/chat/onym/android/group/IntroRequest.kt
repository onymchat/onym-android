package chat.onym.android.group

import java.time.Instant

/**
 * One inbound "request to join" envelope received over an intro
 * inbox tag. Opaque bytes here — decryption + parsing of the inner
 * payload (which carries the joiner's identity pubkey + group
 * acknowledgement) happens in PR-4's `IntroRequestInteractor`.
 *
 * Mirrors the [chat.onym.android.persistence.IncomingInvitationRecord]
 * shape; the two stores are deliberately parallel (identity inbox
 * vs intro inbox) because the consumer flows differ.
 */
data class IntroRequest(
    /** Nostr event id; dedupe key. */
    val id: String,
    /** The inviter's intro pubkey this request was addressed to.
     *  Also doubles as the lookup key into [IntroKeyStore] —
     *  `store.find(targetIntroPub)` returns the privkey that
     *  decrypts [payload]. */
    val targetIntroPublicKey: ByteArray,
    /** Sealed envelope bytes — the inner JSON is encrypted to
     *  [targetIntroPublicKey] via X25519+AES-GCM. */
    val payload: ByteArray,
    val receivedAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntroRequest) return false
        return id == other.id &&
            targetIntroPublicKey.contentEquals(other.targetIntroPublicKey) &&
            payload.contentEquals(other.payload) &&
            receivedAt == other.receivedAt
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + targetIntroPublicKey.contentHashCode()
        h = 31 * h + payload.contentHashCode()
        h = 31 * h + receivedAt.hashCode()
        return h
    }
}
