package app.onym.android.backup

import kotlinx.serialization.Serializable

/**
 * Identifies exact sealed bytes. The digest covers the whole sealed
 * byte sequence of `BackupSealer`'s container format — never local
 * state before sealing, a decoded archive, a locator, or mutable
 * metadata.
 *
 * Mirrors `SnapshotReference` in onym-ios OnymBackup.
 */
@Serializable
data class SnapshotReference(
    val referenceVersion: Int = 1,
    /** [BackupProfile.DIGEST_SUITE]. */
    val algorithm: String = BackupProfile.DIGEST_SUITE,
    /** `sha256:<64 lowercase hex>`. */
    val digest: String,
    /** The padded bucket size of the sealed bytes — a coarse signal,
     *  not a measurement of the underlying history. */
    val sealedByteSize: Long,
) {
    init {
        require(BackupFormat.isDigest(digest)) { "digest is not sha256:<64 lowercase hex>: $digest" }
        require(sealedByteSize >= 0) { "sealedByteSize must be non-negative" }
    }
}
