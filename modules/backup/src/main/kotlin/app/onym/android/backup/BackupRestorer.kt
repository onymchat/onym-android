package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * Restores one sealed snapshot into [sink] over three fixed gates:
 * verify the whole-snapshot reference, decode+validate the entire
 * archive, then write. Any failure at gate 1 or 2 leaves the device
 * completely untouched — nothing is written until both have fully
 * succeeded.
 *
 * Mirrors `BackupRestorer` in onym-ios OnymBackup.
 */
class BackupRestorer(private val sink: BackupSinkProviding) {

    suspend fun restore(
        sealedFile: File,
        reference: SnapshotReference,
        keyMaterial: BackupKeyMaterial,
        workingDirectory: File,
    ): BackupRestoreSummary = withContext(Dispatchers.IO) {
        val plaintextFile = File(workingDirectory, "backup-restore-plain-${randomId()}.tmp")
        try {
            // Gate 1: verify the whole-snapshot reference, then decrypt.
            BackupOpener.open(sealedFile, reference, keyMaterial.archiveRoot, plaintextFile)

            // Gate 2: decode + validate the ENTIRE archive before any write.
            var groups: List<BackupGroupRecord> = emptyList()
            var messages: List<BackupMessageRecord> = emptyList()
            var invitations: List<BackupInvitationRecord> = emptyList()
            var consents: List<BackupConsentRecord> = emptyList()
            val blobs = mutableListOf<BackupBlobRecord>()
            var mediaPolicy = BackupMediaPolicy.DescriptorsOnly

            val reader = BackupArchiveReader(plaintextFile)
            try {
                mediaPolicy = runCatching { BackupMediaPolicy.valueOf(reader.header.mediaPolicy) }
                    .getOrDefault(BackupMediaPolicy.DescriptorsOnly)
                reader.forEachRecord { kind, bytes ->
                    when (kind) {
                        BackupArchiveEntryKind.Groups -> groups = BackupArchiveReader.decodeGroups(bytes)
                        BackupArchiveEntryKind.Messages -> messages = BackupArchiveReader.decodeMessages(bytes)
                        BackupArchiveEntryKind.Invitations -> invitations = BackupArchiveReader.decodeInvitations(bytes)
                        BackupArchiveEntryKind.Consents -> consents = BackupArchiveReader.decodeConsents(bytes)
                        BackupArchiveEntryKind.BlobCiphertext -> {
                            val blob = BackupArchiveReader.decodeBlob(bytes)
                            // Re-verified even though the archive's own per-record
                            // digest already authenticated the framed bytes — this
                            // catches a buggy composer, not just a tampered wire.
                            if (BackupFormat.digest(blob.ciphertext) != "sha256:${blob.sha256}") {
                                throw BackupError.IncompleteSnapshot
                            }
                            blobs += blob
                        }
                    }
                }
            } finally {
                reader.close()
            }

            // Gate 3: only now write, in fixed order — groups before
            // messages so no write ever references a nonexistent group.
            val (writtenGroups, writtenMessages, writtenInvitations, writtenConsents, writtenBlobs) = try {
                val g = sink.restoreGroups(groups)
                val m = sink.restoreMessages(messages)
                val i = sink.restoreInvitations(invitations)
                val c = sink.restoreConsents(consents)
                var b = 0
                for (blob in blobs) {
                    sink.restoreBlob(blob)
                    b++
                }
                WriteCounts(g, m, i, c, b)
            } catch (cancelled: CancellationException) {
                // A cancelled coroutine (navigation away, process
                // death) is not a write failure — rethrow so it
                // propagates as cancellation, not as a reportable
                // RestoreInterrupted the UI would render as an error.
                throw cancelled
            } catch (e: Exception) {
                // Writes are idempotent (insertOrUpdate) so this is
                // explicitly non-atomic-but-safe: earlier writes remain,
                // and this error type tells the caller not to claim
                // nothing changed.
                throw BackupError.LocalFailure(LocalFailureReason.RestoreInterrupted)
            }

            val skipped = buildMap {
                (groups.size - writtenGroups).takeIf { it > 0 }?.let { put("groups", it) }
                (messages.size - writtenMessages).takeIf { it > 0 }?.let { put("messages", it) }
                (invitations.size - writtenInvitations).takeIf { it > 0 }?.let { put("invitations", it) }
                (consents.size - writtenConsents).takeIf { it > 0 }?.let { put("consents", it) }
            }

            val unresolvedBlobs = if (mediaPolicy == BackupMediaPolicy.IncludeCiphertext) {
                val referenced = collectReferencedBlobAddresses(messages)
                val carried = blobs.map { it.sha256 }.toSet()
                (referenced - carried).toList()
            } else {
                emptyList()
            }

            return@withContext BackupRestoreSummary(
                groups = writtenGroups,
                messages = writtenMessages,
                invitations = writtenInvitations,
                consents = writtenConsents,
                blobs = writtenBlobs,
                skipped = skipped,
                unresolvedBlobs = unresolvedBlobs,
            )
        } finally {
            plaintextFile.delete()
        }
    }

    private data class WriteCounts(
        val groups: Int,
        val messages: Int,
        val invitations: Int,
        val consents: Int,
        val blobs: Int,
    )

    private fun randomId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.toLowercaseHex()
}
