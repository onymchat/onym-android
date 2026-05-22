package app.onym.android.identity

/**
 * Public-facing snapshot of one identity. Carries everything the UI
 * needs (label + the two pubkeys it shows) and zero secret material.
 *
 * Lives outside [Identity] because UI lists need the summary for every
 * identity on the device — re-deriving the FFI pubkeys on every render
 * would be wasteful, and listing summaries inside `Identity` would
 * conflate the "currently-active" identity with the "one of N stored"
 * concept.
 */
data class IdentitySummary(
    val id: IdentityId,
    /** User-supplied display name. Defaults to "Identity 1" / "Identity 2"
     *  / etc when the user doesn't pick one at add time; editable in
     *  Settings → Identities (PR-5). */
    val name: String,
    /** 48-byte BLS12-381 G1 compressed pubkey. Same shape as
     *  [Identity.blsPublicKey]. */
    val blsPublicKey: ByteArray,
    /** 32-byte X25519 inbox pubkey. Same shape as
     *  [Identity.inboxPublicKey]. */
    val inboxPublicKey: ByteArray,
    /**
     * 32-byte Ed25519 envelope-signing pubkey. Same shape as
     * [Identity.stellarPublicKey] — same key the receiver verifies
     * sealed envelopes against. Plumbed into every wire-shipped
     * profile so PR A4's chat dispatcher can match a chat envelope's
     * sender to the claimed [app.onym.android.group.MemberProfile]
     * with one direct equality check.
     */
    val sendingPublicKey: ByteArray,
) {
    init {
        require(blsPublicKey.size == 48) {
            "blsPublicKey: expected 48 bytes, got ${blsPublicKey.size}"
        }
        require(inboxPublicKey.size == 32) {
            "inboxPublicKey: expected 32 bytes, got ${inboxPublicKey.size}"
        }
        require(sendingPublicKey.size == 32) {
            "sendingPublicKey: expected 32 bytes, got ${sendingPublicKey.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentitySummary) return false
        return id == other.id &&
            name == other.name &&
            blsPublicKey.contentEquals(other.blsPublicKey) &&
            inboxPublicKey.contentEquals(other.inboxPublicKey) &&
            sendingPublicKey.contentEquals(other.sendingPublicKey)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + blsPublicKey.contentHashCode()
        h = 31 * h + inboxPublicKey.contentHashCode()
        h = 31 * h + sendingPublicKey.contentHashCode()
        return h
    }
}
