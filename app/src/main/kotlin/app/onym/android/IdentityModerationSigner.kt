package app.onym.android

import app.onym.android.identity.IdentityRepository
import app.onym.android.moderation.ModerationSigner
import app.onym.android.moderation.keyReference

/**
 * Adapts [IdentityRepository] behind the moderation seam: the
 * Stellar-derived Ed25519 identity key signs sessions and mandates,
 * and its public half is the `onym:key:<hex>` reference they carry —
 * exact iOS parity (`IdentityModerationSigner` there), so one identity
 * holds one moderation enrollment across platforms. The private key
 * never crosses this boundary; only detached signatures do.
 */
class IdentityModerationSigner(
    private val repository: IdentityRepository,
) : ModerationSigner {
    override suspend fun userKeyId(): String =
        keyReference(repository.bootstrap().stellarPublicKey)

    override suspend fun sign(message: ByteArray): ByteArray =
        repository.signWithStellarKey(message)
}
