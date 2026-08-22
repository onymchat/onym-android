package app.onym.android

import app.onym.android.identity.IdentityRepository
import app.onym.android.moderation.keyReference
import app.onym.android.push.PushSigner

/**
 * Adapts [IdentityRepository] behind the push seam, exactly as
 * [IdentityModerationSigner] does for moderation: the Stellar-derived
 * Ed25519 identity key signs registration sessions, and its public
 * half is the `onym:key:<hex>` reference they carry — the same
 * reference across platforms and seats, so one identity holds one
 * push registration. The private key never crosses this boundary;
 * only detached signatures do.
 */
class IdentityPushSigner(
    private val repository: IdentityRepository,
) : PushSigner {
    override suspend fun userKeyId(): String =
        keyReference(repository.bootstrap().stellarPublicKey)

    override suspend fun sign(message: ByteArray): ByteArray {
        // Await the (idempotent) bootstrap first — a reconcile pass
        // scheduled at app start can outrun the async identity
        // bootstrap, and signWithStellarKey alone would then throw.
        repository.bootstrap()
        return repository.signWithStellarKey(message)
    }
}
