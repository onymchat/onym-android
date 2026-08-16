package app.onym.android.identity

/**
 * Narrow signing seam for the chat send path: detached Ed25519
 * signatures by the active identity's Stellar-derived key — the same
 * key a peer stores as `MemberProfile.sendingPubkey` and a moderation
 * Authority resolves from an `onym:key:<hex>` reference, so bytes
 * signed here verify against both.
 *
 * [SendMessageInteractor] depends on this instead of the concrete
 * [IdentityRepository] for the same reason it takes
 * [InvitationEnvelopeSealer]: unit-testable without standing up the
 * real keychain, and the private key never crosses the seam — only
 * detached signatures do.
 *
 * iOS has no equivalent seam (its interactor holds
 * `IdentityRepository` directly); the narrow interface is this
 * repo's established idiom.
 */
interface IdentityMessageSigner {
    /** Detached Ed25519 signature over [message] by the currently
     *  selected identity's Stellar-derived key. Throws
     *  [IdentityError.IdentityNotLoaded] when no identity is
     *  loaded. */
    suspend fun signWithStellarKey(message: ByteArray): ByteArray
}
