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
 * Static helpers reading the active pinned consent for the backup
 * seat. Entitlement-issuer keys are resolved **from the consented
 * manifest**, never from a presented credential — a credential must
 * never be able to nominate its own authority.
 *
 * Mirrors `BackupSeat` in onym-ios.
 */
object BackupSeat {

    suspend fun activeConsent(consentStore: PinnedConsentStore): PinnedConsentRecord? =
        consentStore.load().firstOrNull { it.seatType == BACKUP_SEAT_TYPE && it.isActive }

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
