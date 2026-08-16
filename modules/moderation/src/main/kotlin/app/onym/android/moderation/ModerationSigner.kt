package app.onym.android.moderation

/**
 * Identity seam: how moderation objects get user signatures without
 * this module depending on :identity. The app target adapts
 * `IdentityRepository` behind this — the private key never crosses the
 * boundary, only detached signatures do.
 *
 * The key is the Stellar-derived Ed25519 identity key, exactly as on
 * iOS: one identity, one `onym:key:<hex>` reference, across platforms.
 */
interface ModerationSigner {
    /** The user's identity key reference as mandates carry it:
     * `onym:key:<hex>`. */
    suspend fun userKeyId(): String

    /** Ed25519 detached signature over [message]. */
    suspend fun sign(message: ByteArray): ByteArray
}
