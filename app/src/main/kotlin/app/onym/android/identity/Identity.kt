package app.onym.android.identity

/**
 * Snapshot of the user's persisted identity, as projected to UI and
 * other subsystems. Immutable, safe to pass across threads.
 *
 * Two of the keypairs are persisted (nostr secp256k1, BLS12-381) and
 * two are HKDF-derived from the nostr secret on every load (Stellar
 * Ed25519 for on-chain identity + envelope signing, X25519 for invitation
 * ECDH). The private halves of the derived pairs stay inside the
 * repository; only the pubkeys/identifiers projected here are visible
 * to callers.
 *
 * **All six fields are deterministic** from the persisted secrets — the
 * repo computes them once at load and ships precomputed bytes; views
 * never trigger HKDF or an FFI call.
 */
data class Identity(
    /** 32-byte BIP340 x-only secp256k1 public key (Nostr npub source). */
    val nostrPublicKey: ByteArray,
    /** 48-byte BLS12-381 G1 compressed public key (SEP group membership). */
    val blsPublicKey: ByteArray,
    /**
     * 32-byte Ed25519 public key (raw representation). Doubles as the
     * Stellar account public key and the verifying key for envelope
     * signatures + transport-bundle binding signatures.
     */
    val stellarPublicKey: ByteArray,
    /**
     * Stellar StrKey account ID (`G…`, 56 chars), used as
     * `callerAddress` on every Soroban contract call.
     */
    val stellarAccountID: String,
    /**
     * 32-byte X25519 public key (raw representation). Permanent ECDH
     * key — senders ECDH against this to encrypt invitations to us.
     */
    val inboxPublicKey: ByteArray,
    /**
     * 16-char hex of `SHA-256("sep-inbox-v1" || inboxPublicKey)[0..8]`.
     * Discoverable inbox handle posted as a Nostr `#t` / `#d` filter
     * tag so peers can address invites without leaking the X25519
     * pubkey on-relay.
     */
    val inboxTag: String,
    /**
     * 12-word BIP39 recovery phrase, or `null` for an identity that
     * was loaded from raw key material without an associated mnemonic.
     */
    val recoveryPhrase: String?,
) {
    // Default data-class equals/hashCode use reference equality for
    // ByteArray fields. Override with content-based comparison so two
    // Identity instances with the same bytes compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return nostrPublicKey.contentEquals(other.nostrPublicKey) &&
                blsPublicKey.contentEquals(other.blsPublicKey) &&
                stellarPublicKey.contentEquals(other.stellarPublicKey) &&
                stellarAccountID == other.stellarAccountID &&
                inboxPublicKey.contentEquals(other.inboxPublicKey) &&
                inboxTag == other.inboxTag &&
                recoveryPhrase == other.recoveryPhrase
    }

    override fun hashCode(): Int {
        var result = nostrPublicKey.contentHashCode()
        result = 31 * result + blsPublicKey.contentHashCode()
        result = 31 * result + stellarPublicKey.contentHashCode()
        result = 31 * result + stellarAccountID.hashCode()
        result = 31 * result + inboxPublicKey.contentHashCode()
        result = 31 * result + inboxTag.hashCode()
        result = 31 * result + (recoveryPhrase?.hashCode() ?: 0)
        return result
    }
}
