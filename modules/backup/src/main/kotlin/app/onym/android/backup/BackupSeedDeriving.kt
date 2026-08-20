package app.onym.android.backup

/**
 * The seam `:backup` uses to reach seed-scoped key material without
 * depending on `:identity` — implemented by `IdentityRepository` at
 * the composition root. `info` must be one of the strings this module
 * derives with (see [BackupKeys]); the conformer enforces its own
 * allowlist independently (see `IdentityRepository.deriveSeedScopedKey`).
 *
 * @return exactly 32 bytes.
 *
 * Mirrors `BackupSeedDeriving` in onym-ios OnymBackup.
 */
interface BackupSeedDeriving {
    suspend fun deriveSeedScopedKey(info: String): ByteArray
}
