package app.onym.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class BackupArchiveMultiBlobTest {

    private fun tempFile(prefix: String) = File.createTempFile(prefix, ".bin").apply { deleteOnExit() }

    @Test
    fun archive_with_two_or_more_blobs_round_trips_without_being_refused() {
        val scratch = tempFile("scratch")
        val archiveFile = tempFile("archive")
        val writer = BackupArchiveWriter(scratch)
        writer.setMediaPolicy(BackupMediaPolicy.IncludeCiphertext)

        val blobA = BackupBlobRecord("a".repeat(64), "content-a".toByteArray())
        val blobB = BackupBlobRecord("b".repeat(64), "content-b".toByteArray())
        val blobC = BackupBlobRecord("c".repeat(64), "content-c".toByteArray())
        writer.appendBlob(blobA)
        writer.appendBlob(blobB)
        writer.appendBlob(blobC)
        writer.finish(archiveFile, Instant.now())

        val reader = BackupArchiveReader(archiveFile)
        val decoded = mutableListOf<BackupBlobRecord>()
        try {
            reader.forEachRecord { kind, bytes ->
                assertEquals(BackupArchiveEntryKind.BlobCiphertext, kind)
                decoded += BackupArchiveReader.decodeBlob(bytes)
            }
        } finally {
            reader.close()
        }

        assertEquals(3, decoded.size)
        assertTrue(decoded.any { it.sha256 == blobA.sha256 && it.ciphertext.contentEquals(blobA.ciphertext) })
        assertTrue(decoded.any { it.sha256 == blobB.sha256 && it.ciphertext.contentEquals(blobB.ciphertext) })
        assertTrue(decoded.any { it.sha256 == blobC.sha256 && it.ciphertext.contentEquals(blobC.ciphertext) })
    }

    @Test
    fun a_repeated_non_blob_kind_is_still_refused() {
        // The exemption is specific to BlobCiphertext — every other
        // kind still fails closed on a repeated header entry.
        val scratch = tempFile("scratch2")
        val archiveFile = tempFile("archive2")
        val writer = BackupArchiveWriter(scratch)
        writer.appendGroups(listOf(BackupGroupRecord("g1", "id1", "{}", 1000L)))
        writer.finish(archiveFile, Instant.now())

        // Hand-craft a header with two Groups entries by re-reading and
        // duplicating the entry list, since the writer itself never
        // produces this — this simulates a corrupted/hostile header.
        val reader = BackupArchiveReader(archiveFile)
        reader.close()
        val header = reader.header
        val corrupted = header.copy(entries = header.entries + header.entries)
        val corruptedBytes = kotlinx.serialization.json.Json.encodeToString(
            BackupArchiveHeader.serializer(),
            corrupted,
        ).toByteArray(Charsets.UTF_8)

        val original = archiveFile.readBytes()
        // original = magic(8) + headerLength(4) + headerBytes + records...
        val originalHeaderLength = java.nio.ByteBuffer.wrap(original, 8, 4).int
        val recordsStart = 8 + 4 + originalHeaderLength
        val records = original.copyOfRange(recordsStart, original.size)

        val rebuilt = File.createTempFile("rebuilt", ".bin").apply { deleteOnExit() }
        rebuilt.outputStream().use { out ->
            out.write("ONYMBAK1".toByteArray(Charsets.US_ASCII))
            out.write(java.nio.ByteBuffer.allocate(4).putInt(corruptedBytes.size).array())
            out.write(corruptedBytes)
            out.write(records)
            // duplicate the records bytes too, so the stream actually
            // has two groups entries to (mis)read
            out.write(records)
        }

        val corruptedReader = BackupArchiveReader(rebuilt)
        try {
            org.junit.Assert.assertThrows(BackupError.IncompleteSnapshot::class.java) {
                corruptedReader.forEachRecord { _, _ -> }
            }
        } finally {
            corruptedReader.close()
        }
    }
}
