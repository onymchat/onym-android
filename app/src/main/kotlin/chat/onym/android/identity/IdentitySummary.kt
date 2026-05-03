package chat.onym.android.identity

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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentitySummary) return false
        return id == other.id &&
            name == other.name &&
            blsPublicKey.contentEquals(other.blsPublicKey) &&
            inboxPublicKey.contentEquals(other.inboxPublicKey)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + blsPublicKey.contentHashCode()
        h = 31 * h + inboxPublicKey.contentHashCode()
        return h
    }
}
