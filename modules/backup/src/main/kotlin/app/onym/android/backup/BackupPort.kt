package app.onym.android.backup

import java.io.File
import java.time.Instant

/** A snapshot the operator confirms holding, as returned by `listSnapshots`. */
data class RetainedSnapshot(
    val snapshotReference: SnapshotReference,
    val acceptedTermsId: String,
    val retainedAt: Instant,
    val retainedUntil: Instant?,
    val supersedes: SnapshotReference?,
    val status: String,
)

/** Readiness signal from `connect`. */
data class BackupConnection(val acceptedTermsId: String?)

/** The grant returned by a successful `preflight` — what an upload may
 *  send. Checked against the sealed file's actual size before any
 *  byte moves; never trusted blindly. */
data class BackupUploadGrant(
    val uploadId: String,
    val chunkBytes: Long,
    val chunkCount: Long,
    val expiresAt: Instant,
)

/** The outcome of a `preflight` call — either a grant to upload, or an
 *  outcome that already answers the operation (e.g. `already_retained`). */
sealed class BackupPreflight {
    data class Grant(val upload: BackupUploadGrant) : BackupPreflight()
    data class Resolved(val outcome: BackupOutcome) : BackupPreflight()
}

/** Every retained snapshot's sealed bytes in the profile's portable
 *  export container, plus its references, terms, and receipts. */
data class BackupExport(
    val exportedAt: Instant,
    val directory: File,
)

/**
 * The common Onym backup surface, technology-neutral (onym-system
 * `backup/UI-Backup.md` §6). Carries only sealed bytes, references,
 * termsId, and typed outcomes across it — no wire-specific object,
 * HTTP status, or locator format ever crosses this boundary.
 *
 * `ObjectHttpBackupClient` is the only conformer in this codebase; the
 * abstraction exists so the wire mapping can be replaced without
 * touching the repository or the UI.
 *
 * Mirrors `BackupPort` in onym-ios OnymBackup.
 */
interface BackupPort {
    suspend fun connect(): BackupConnection
    suspend fun preflight(snapshot: SealedSnapshot): BackupPreflight
    suspend fun uploadSnapshot(snapshot: SealedSnapshot, grant: BackupUploadGrant): BackupOutcome
    suspend fun listSnapshots(): List<RetainedSnapshot>
    suspend fun downloadSnapshot(reference: SnapshotReference, destination: File)
    suspend fun eraseSnapshot(scope: ErasureScope): ErasureReceipt
    suspend fun exportSnapshots(directory: File): BackupExport
    suspend fun queryOutcome(operationId: String): BackupOutcome?
}
