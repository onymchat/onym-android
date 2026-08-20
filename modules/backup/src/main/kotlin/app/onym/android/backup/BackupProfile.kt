package app.onym.android.backup

/**
 * Pinned suite/profile identifiers — the exact strings a conforming
 * operator publishes and a conforming client checks for exact
 * equality. Any mismatch is a different protocol under the same name,
 * not a compatible variant.
 *
 * Values pinned in onym-system `backup/UI-Backup.md` §5.1-5.2 (the
 * portable profile) and `backup/UI-Backup-Object-HTTP.md` §1, §3 (the
 * object-HTTP implementation profile this client speaks).
 *
 * Mirrors `BackupProfile` in onym-ios OnymBackup.
 */
object BackupProfile {
    const val PORTABLE_PROFILE_ID = "onym:backup-profile:sealed-device-archive-v1"
    const val IMPLEMENTATION_PROFILE_ID = "onym:backup-implementation:object-http-v1"
    const val DIGEST_SUITE = "sha-256/lowercase-hex"
    const val SEALING_SUITE = "aes-256-gcm/hkdf-sha256-from-bip39-seed"
    const val INCREMENT_MODEL = "whole-snapshot-transfer-chunked-v1"
    const val AUTHENTICATION = "holder-scoped-capability/ed25519-request-bound-v1"
    const val PAYMENT_REFUSAL = "onym-payment-required-v1"

    val REQUIRED_OPERATIONS: Set<String> = setOf("upload", "list", "download", "erase", "export")
}

/** The operator-published `BackupImplementationProfile` — see
 *  `backup/UI-Backup.md` §5.2. `isSupported` requires every pinned
 *  suite field to match exactly; a partial match is treated as an
 *  incompatible profile, not a degraded one. */
data class BackupImplementationProfile(
    val implementationVersion: Int,
    val implementationProfileId: String,
    val backupProfileId: String,
    val wireProtocol: String,
    val digestSuite: String,
    val sealingSuite: String,
    val incrementModel: String,
    val authentication: List<String>,
    val paymentRefusal: String,
) {
    val isSupported: Boolean
        get() = implementationProfileId == BackupProfile.IMPLEMENTATION_PROFILE_ID &&
            backupProfileId == BackupProfile.PORTABLE_PROFILE_ID &&
            digestSuite == BackupProfile.DIGEST_SUITE &&
            sealingSuite == BackupProfile.SEALING_SUITE &&
            incrementModel == BackupProfile.INCREMENT_MODEL &&
            authentication.contains(BackupProfile.AUTHENTICATION) &&
            paymentRefusal == BackupProfile.PAYMENT_REFUSAL
}
