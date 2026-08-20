package app.onym.android.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

private class FakeSource(
    private val groupRecords: List<BackupGroupRecord>,
    private val messagesByGroup: Map<String, List<BackupMessageRecord>>,
    private val blobs: Map<String, ByteArray> = emptyMap(),
) : BackupSourceProviding {
    override suspend fun identityCount(): Int = 1
    override suspend fun groups(): List<BackupGroupRecord> = groupRecords
    override suspend fun messages(groupId: String, ownerIdentityId: String): List<BackupMessageRecord> =
        messagesByGroup[groupId].orEmpty()
    override suspend fun invitations(): List<BackupInvitationRecord> = emptyList()
    override suspend fun consents(): List<BackupConsentRecord> = emptyList()
    override suspend fun blobCiphertext(sha256: String): BackupBlobAvailability =
        blobs[sha256]?.let { BackupBlobAvailability.Available(it) } ?: BackupBlobAvailability.Gone
}

/**
 * @param groupCapacity how many of the groups handed over this sink
 *   can reconstruct; the remainder it reports as `unreadable`.
 * @param groupsAlreadyHeld groups it reports as landed *without*
 *   inserting — the ordinary outcome of an idempotent write onto a
 *   device that already holds the row, which must never be reported
 *   as a failure to restore.
 */
private class RecordingSink(
    private val groupCapacity: Int = Int.MAX_VALUE,
    private val groupsAlreadyHeld: Set<String> = emptySet(),
) : BackupSinkProviding {
    val writtenGroups = mutableListOf<BackupGroupRecord>()
    val writtenMessages = mutableListOf<BackupMessageRecord>()
    val writtenBlobs = mutableListOf<BackupBlobRecord>()

    override suspend fun restoreGroups(records: List<BackupGroupRecord>): BackupSinkOutcome {
        val readable = records.take(groupCapacity)
        writtenGroups += readable.filter { it.groupId !in groupsAlreadyHeld }
        return BackupSinkOutcome(landed = readable.size, unreadable = records.size - readable.size)
    }
    override suspend fun restoreMessages(records: List<BackupMessageRecord>): BackupSinkOutcome {
        writtenMessages += records
        return BackupSinkOutcome(landed = records.size, unreadable = 0)
    }
    override suspend fun restoreInvitations(records: List<BackupInvitationRecord>): BackupSinkOutcome =
        BackupSinkOutcome(landed = records.size, unreadable = 0)
    override suspend fun restoreConsents(records: List<BackupConsentRecord>): BackupSinkOutcome =
        BackupSinkOutcome(landed = records.size, unreadable = 0)
    override suspend fun restoreBlob(record: BackupBlobRecord) {
        writtenBlobs += record
    }
}

class BackupCompositionTest {

    private fun tempDir(): File = File.createTempFile("backup-test", "").apply {
        delete(); mkdirs(); deleteOnExit()
    }

    private fun keyMaterial(): BackupKeyMaterial {
        val archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return BackupKeyMaterial(
            archiveRoot = archiveRoot,
            accessSigningKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(SecureRandom()),
            accessAgreementKey = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(SecureRandom()),
            rotation = 0,
        )
    }

    @Test
    fun compose_restore_round_trip() = runTest {
        val group = BackupGroupRecord("g1", "id1", "{\"members\":[]}", 1000L)
        val message = BackupMessageRecord("m1", "g1", "id1", "abc", 1001L, "{\"text\":\"hi\"}")
        val source = FakeSource(listOf(group), mapOf("g1" to listOf(message)))
        val workDir = tempDir()
        val composer = BackupComposer(source, BackupMediaPolicy.DescriptorsOnly, workDir)
        val keys = keyMaterial()

        val sealed = composer.compose(keys, acceptedTermsId = "sha256:" + "a".repeat(64))
        assertTrue(sealed.sealedBytesFile.exists())
        assertFalse(File(workDir, "backup-plain-${sealed.operationId}.tmp").exists())

        val sink = RecordingSink()
        val restorer = BackupRestorer(sink)
        val summary = restorer.restore(sealed.sealedBytesFile, sealed.snapshotReference, keys, workDir)

        assertEquals(1, summary.groups)
        assertEquals(1, summary.messages)
        assertEquals(1, sink.writtenGroups.size)
        assertEquals("g1", sink.writtenGroups[0].groupId)
        assertTrue(summary.skipped.isEmpty())
        assertTrue(summary.unresolvedBlobs.isEmpty())
    }

    @Test
    fun summary_counts_what_the_sink_actually_wrote() = runTest {
        val groups = listOf(
            BackupGroupRecord("g1", "id1", "{}", 1000L),
            BackupGroupRecord("g2", "id1", "{}", 1000L),
        )
        val source = FakeSource(groups, emptyMap())
        val workDir = tempDir()
        val keys = keyMaterial()
        val sealed = BackupComposer(source, BackupMediaPolicy.DescriptorsOnly, workDir)
            .compose(keys, acceptedTermsId = "sha256:" + "b".repeat(64))

        val refusingSink = RecordingSink(groupCapacity = 1)
        val summary = BackupRestorer(refusingSink).restore(sealed.sealedBytesFile, sealed.snapshotReference, keys, workDir)

        assertEquals(1, summary.groups)
        assertEquals(1, summary.skipped["groups"])
    }

    @Test
    fun rows_the_device_already_holds_are_restored_not_skipped() = runTest {
        // The regression this guards: the summary used to be
        // `archiveCount - written`, so restoring an archive whose rows
        // the device already had reported every one of them under
        // "This version of the app couldn't restore…" — a second
        // restore of the same archive claimed 100% failure having
        // succeeded completely. Nothing here is unreadable, so nothing
        // may be skipped.
        val groups = listOf(
            BackupGroupRecord("g1", "id1", "{}", 1000L),
            BackupGroupRecord("g2", "id1", "{}", 1000L),
        )
        val source = FakeSource(groups, emptyMap())
        val workDir = tempDir()
        val keys = keyMaterial()
        val sealed = BackupComposer(source, BackupMediaPolicy.DescriptorsOnly, workDir)
            .compose(keys, acceptedTermsId = "sha256:" + "d".repeat(64))

        val sink = RecordingSink(groupsAlreadyHeld = setOf("g1", "g2"))
        val summary = BackupRestorer(sink).restore(sealed.sealedBytesFile, sealed.snapshotReference, keys, workDir)

        assertEquals("both groups are on the device", 2, summary.groups)
        assertTrue("nothing was inserted", sink.writtenGroups.isEmpty())
        assertTrue("…and nothing is unreadable", summary.skipped.isEmpty())
    }

    @Test
    fun archive_contains_no_seed_material() = runTest {
        // The archive root itself stands in for seed-derived material
        // here (no real BIP39 seed reachable from :backup — the real
        // BackupSeedDeriving conformer lives in :identity). What this
        // asserts is the structural guarantee: BackupComposer never
        // has access to anything beyond BackupSourceProviding, and the
        // key material bytes it seals with never appear in the
        // plaintext archive.
        val keys = keyMaterial()
        val group = BackupGroupRecord("g1", "id1", "{}", 1000L)
        val source = FakeSource(listOf(group), emptyMap())
        val workDir = tempDir()
        val sealed = BackupComposer(source, BackupMediaPolicy.DescriptorsOnly, workDir)
            .compose(keys, acceptedTermsId = "sha256:" + "c".repeat(64))

        val restoredPlain = File(workDir, "probe-plain.tmp")
        BackupOpener.open(sealed.sealedBytesFile, sealed.snapshotReference, keys.archiveRoot, restoredPlain)
        val bytes = restoredPlain.readBytes()

        assertFalse(containsSubsequence(bytes, keys.archiveRoot))
        val signingBytes = ByteArray(32)
        // Ed25519PrivateKeyParameters doesn't expose raw bytes directly in
        // all BC versions without encoding; this checks the encoded form.
        val encoded = keys.accessSigningKey.encoded
        assertFalse(containsSubsequence(bytes, encoded))
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }
}
