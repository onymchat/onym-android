package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import app.onym.android.foundation.Bip39
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest

/**
 * The device-held seat key material for one operator: the archive
 * root (what every snapshot's key is stretched from), and the
 * access-signing / access-agreement keypairs the operator and the
 * billing broker respectively see. Never derived from the identity's
 * Nostr/BLS/Stellar/inbox keys, and unlinkable to them.
 */
class BackupKeyMaterial(
    val archiveRoot: ByteArray,
    val accessSigningKey: Ed25519PrivateKeyParameters,
    val accessAgreementKey: X25519PrivateKeyParameters,
    val rotation: Int,
)

/**
 * All backup key derivation — HKDF-SHA256 from the holder's BIP39
 * seed, salt `"app.onym.bip39"` (the same salt every other seed-scoped
 * derivation in this codebase uses). `componentId` is baked into the
 * signing/agreement `info` strings: two operators derive two unrelated
 * keypairs from the same seed, so neither key correlates a holder
 * across operators.
 *
 * Every string constant here is a cross-platform interop contract —
 * see the parameter table in the implementation plan. Do not change
 * without a matching change on iOS and in onym-system's
 * `backup/UI-Backup-Object-HTTP.md`.
 *
 * Mirrors `BackupKeys` in onym-ios OnymBackup.
 */
object BackupKeys {
    private const val ARCHIVE_INFO = "backup-archive-v1"
    private const val SNAPSHOT_INFO = "backup-snapshot-v1"
    private const val SIGNING_INFO_PREFIX = "backup-access-ed25519-v1"
    private const val AGREEMENT_INFO_PREFIX = "backup-access-x25519-v1"
    private val HANDLE_CONTEXT = "onym-backup-holder-v1".toByteArray(Charsets.UTF_8)

    /** Prefix for the wire holder identifier — deliberately distinct
     *  from `onym:key:`/`onym:component:` so the wire itself states
     *  "this key is seat-scoped, not an identity key." */
    const val HOLDER_REFERENCE_PREFIX = "onym:seat-key:"

    fun signingInfo(componentId: String, rotation: Int): String =
        "$SIGNING_INFO_PREFIX|$componentId|r$rotation"

    fun agreementInfo(componentId: String, rotation: Int): String =
        "$AGREEMENT_INFO_PREFIX|$componentId|r$rotation"

    /** Derives the full [BackupKeyMaterial] for one operator/rotation
     *  via [deriving]. @throws BackupError.LocalFailure(KeyMaterialInvalid)
     *  if any derived value isn't exactly 32 bytes. */
    suspend fun material(
        deriving: BackupSeedDeriving,
        componentId: String,
        rotation: Int = 0,
    ): BackupKeyMaterial {
        val archiveRoot = deriving.deriveSeedScopedKey(ARCHIVE_INFO)
        val signingSeed = deriving.deriveSeedScopedKey(signingInfo(componentId, rotation))
        val agreementSeed = deriving.deriveSeedScopedKey(agreementInfo(componentId, rotation))
        if (archiveRoot.size != 32 || signingSeed.size != 32 || agreementSeed.size != 32) {
            throw BackupError.LocalFailure(LocalFailureReason.KeyMaterialInvalid)
        }
        return BackupKeyMaterial(
            archiveRoot = archiveRoot,
            accessSigningKey = Ed25519PrivateKeyParameters(signingSeed, 0),
            accessAgreementKey = X25519PrivateKeyParameters(agreementSeed, 0),
            rotation = rotation,
        )
    }

    /** Per-snapshot key: HKDF-SHA256 with [archiveRoot] as ikm and the
     *  snapshot's own 32-byte random salt as the HKDF *salt* parameter
     *  (not folded into `info`). Fresh per snapshot — this is what
     *  makes sealing non-convergent. */
    fun snapshotKey(archiveRoot: ByteArray, snapshotSalt: ByteArray): ByteArray =
        Bip39.hkdfSha256(
            ikm = archiveRoot,
            salt = snapshotSalt,
            info = SNAPSHOT_INFO.toByteArray(Charsets.UTF_8),
            length = 32,
        )

    fun holderReference(signingPublicKeyRaw: ByteArray): String =
        HOLDER_REFERENCE_PREFIX + signingPublicKeyRaw.toLowercaseHex()

    /** `sha256(context ‖ rawPublicKey)`, lowercase hex, no prefix —
     *  what an operator stores/shards on, distinct from the raw
     *  public key that still appears in request headers (it must, to
     *  verify a signature) but is never itself persisted or logged. */
    fun holderHandle(signingPublicKeyRaw: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(HANDLE_CONTEXT + signingPublicKeyRaw)
            .toLowercaseHex()
}
