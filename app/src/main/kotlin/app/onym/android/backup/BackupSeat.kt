package app.onym.android.backup

import app.onym.android.backup.BackupOperatorManifest
import app.onym.android.foundation.PinnedConsentRecord
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.foundation.SignedServiceManifest
import java.io.File

/** The seat identifier this feature consents under, per onym-system
 *  `backup/UI-Backup.md` §5.3. */
const val BACKUP_SEAT_TYPE = "storage.backup"

/**
 * Static helpers reading the active pinned consent(s) for the backup
 * seat. Entitlement-issuer keys are resolved **from the consented
 * manifest**, never from a presented credential — a credential must
 * never be able to nominate its own authority.
 *
 * A holder may consent to several backup operators at once —
 * `PinnedConsentStore.accept()` only deactivates a PRIOR record for
 * the SAME componentId, never records for other components under the
 * same seat — so this seat is multi-vendor by construction: every key
 * derivation is already scoped per componentId (see `BackupKeys`),
 * and each vendor gets its own independent working directory, wire
 * client, and persisted state (see `BackupSeatComposer.composeAll`).
 *
 * Mirrors `BackupSeat` in onym-ios.
 */
object BackupSeat {

    /** Every currently-active backup consent, oldest first — one per
     *  vendor the holder has backed up to. */
    suspend fun activeConsents(consentStore: PinnedConsentStore): List<PinnedConsentRecord> =
        consentStore.load().filter { it.seatType == BACKUP_SEAT_TYPE && it.isActive }

    fun manifest(record: PinnedConsentRecord): BackupOperatorManifest? = runCatching {
        BackupOperatorManifest.from(SignedServiceManifest.parse(record.manifestBytes))
    }.getOrNull()

    /** On-disk working directory for one operator's in-flight sealing
     *  scratch files and pending-payment sealed snapshots. Lives under
     *  the app's persistent (not cache) storage — a pending payment
     *  retry must survive a process death. */
    fun workingDirectory(filesDir: File, componentId: String): File =
        File(filesDir, "backup/${componentId.substringAfterLast(':')}").apply { mkdirs() }

    fun blobDirectory(filesDir: File, componentId: String): File =
        File(workingDirectory(filesDir, componentId), "blobs").apply { mkdirs() }
}
