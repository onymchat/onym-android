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

    /**
     * Batched form of [deriveSeedScopedKey] — derives every key in
     * [infos] from ONE seed load instead of one per key. The default
     * implementation is the naive N-call fallback; a conformer that
     * can amortize the seed load (BIP39 seed derivation is a 2048-
     * round PBKDF2-HMAC-SHA512 stretch, not cheap) should override
     * this. [BackupKeys.material] always calls this — never the
     * single-key form directly — for exactly that reason: composing
     * one operator's key material needs three keys (archive root,
     * access signing, access agreement), and a device backing up to
     * several operators pays this cost once per operator per app
     * launch.
     */
    suspend fun deriveSeedScopedKeys(infos: List<String>): List<ByteArray> = infos.map { deriveSeedScopedKey(it) }
}
