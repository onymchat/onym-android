package app.onym.android.backup

import java.time.Instant

/**
 * A signed commitment measured against the pinned terms — not proof
 * of destruction. `excludedScope` is mandatory and non-empty: there is
 * always something an erasure does not reach (at minimum, copies held
 * by other participants and copies the holder exported), and a
 * receipt with an empty `excludedScope` is malformed rather than
 * generous.
 *
 * Mirrors `ErasureReceipt` in onym-ios OnymBackup and the
 * `onym-backup-erasure-receipt-v1` schema in
 * `backup/UI-Backup-Object-HTTP.md` §11.
 */
data class ErasureReceipt(
    val receiptVersion: Int = 1,
    val receiptId: String,
    val operator: String,
    val scope: String,
    val acknowledgedAt: Instant,
    val completionCommittedBy: Instant,
    val coveredScope: String,
    val excludedScope: String,
    val termsId: String,
    val signature: String,
) {
    init {
        require(excludedScope.isNotBlank()) {
            "excludedScope must be non-empty — there is always something an erasure does not reach"
        }
    }

    /** `true` until the receipt's committed completion deadline
     *  passes without contradiction. An operator missing this deadline
     *  has violated the terms it signed — detected from the receipt
     *  already held, never from a status code the operator would have
     *  to volunteer against its own interest. */
    fun isCompletionPending(now: Instant = Instant.now()): Boolean = now.isBefore(completionCommittedBy)
}
