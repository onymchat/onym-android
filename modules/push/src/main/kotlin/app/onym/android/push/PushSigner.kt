package app.onym.android.push

/**
 * Identity seam: how push sessions get user signatures without this
 * module depending on :identity. The app target adapts
 * `IdentityRepository` behind this — the private key never crosses
 * the boundary, only detached signatures do. Same shape as
 * :moderation's `ModerationSigner`.
 */
interface PushSigner {
    /** The user's identity key reference: `onym:key:<hex>`. */
    suspend fun userKeyId(): String

    /** Ed25519 detached signature over [message]. */
    suspend fun sign(message: ByteArray): ByteArray
}
