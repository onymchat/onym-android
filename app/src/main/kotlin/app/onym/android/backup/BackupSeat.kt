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
 * same seat — so this seat is multi-vendor by construction: each
 * vendor gets its own independent working directory, wire client, and
 * persisted state (see `BackupSeatComposer.composeAll`).
 *
 * The anti-correlation property this depends on is narrower than "every
 * key is scoped per componentId": only the ACCESS SIGNING and ACCESS
 * AGREEMENT keys are componentId-scoped (`BackupKeys.signingInfo`/
 * `agreementInfo`) — those are what an operator or broker actually
 * sees, and the pinned reason two operators must derive unrelated
 * keypairs from them. The ARCHIVE ROOT (`BackupKeys.ARCHIVE_INFO`) is
 * deliberately shared across every vendor, per onym-system
 * `backup/UI-Backup-Object-HTTP.md` §5.2's derivation table — it is
 * never presented to an operator, and every snapshot draws a fresh
 * random 32-byte salt before deriving its own key from that shared
 * root (`BackupKeys.snapshotKey`), so no two snapshots — same vendor
 * or different — ever share a key even though they share a root.
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
     *  retry must survive a process death.
     *
     *  Keyed by a hash of the FULL componentId, not a trailing
     *  substring — `SignedServiceManifest.parse` already constrains a
     *  validated componentId to a single colon-free local segment, so
     *  two DIFFERENT componentIds can't collide today, but this
     *  doesn't want to depend on that invariant holding in a different
     *  code path or a future format relaxation. */
    fun workingDirectory(filesDir: File, componentId: String): File =
        File(filesDir, "backup/${componentDirectoryName(componentId)}").apply { mkdirs() }

    fun blobDirectory(filesDir: File, componentId: String): File =
        File(workingDirectory(filesDir, componentId), "blobs").apply { mkdirs() }

    private fun componentDirectoryName(componentId: String): String =
        BackupFormat.digest(componentId.toByteArray(Charsets.UTF_8)).removePrefix("sha256:").take(32)
}
