package app.onym.android.backup

import java.io.File
import java.time.Instant

/**
 * A sealed, ready-to-upload snapshot. The plaintext archive never
 * survives past sealing — [sealedBytesFile] holds only sealed bytes on
 * disk, never in memory, so a multi-hundred-megabyte history doesn't
 * have to fit in the heap.
 *
 * Mirrors `SealedSnapshot` in onym-ios OnymBackup.
 */
data class SealedSnapshot(
    val snapshotVersion: Int = 1,
    /** Idempotency key across retries — the same value is reused on a
     *  payment-refusal retry so re-sealing (which would mint a new
     *  `snapshotSalt` and therefore a new digest) never happens. */
    val operationId: String,
    val snapshotReference: SnapshotReference,
    val sealedBytesFile: File,
    val sealedAt: Instant,
    /** The `BackupTerms` digest this snapshot pins — forward-only
     *  binding, never re-pinned without fresh consent. */
    val acceptedTermsId: String,
    val supersedes: SnapshotReference? = null,
)

/** What an erasure request targets. */
sealed class ErasureScope {
    data class Snapshot(val reference: SnapshotReference) : ErasureScope()
    data object All : ErasureScope()
}
