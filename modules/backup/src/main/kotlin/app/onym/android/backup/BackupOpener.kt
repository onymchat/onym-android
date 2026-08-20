package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verify-then-decrypt, strict order: (1) size check, (2) full-file
 * SHA-256 recomputed and compared to the claimed reference *before
 * touching any key material*, (3) only then header parsed and chunks
 * decrypted. Per-chunk AEAD authentication says nothing about chunk
 * *order*, so the nonce is separately checked against the expected
 * counter for its position.
 *
 * Mirrors `BackupOpener` in onym-ios OnymBackup.
 */
object BackupOpener {
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAGIC = "ONYMSEAL1"
    /** Sane cap on `chunkCount` so a corrupted or malicious header
     *  can't drive an unbounded allocation before the digest check
     *  (which runs first) has any chance to catch it — this bound is
     *  independent of and looser than that check, purely a guard on
     *  header parsing itself. */
    private const val MAX_CHUNK_COUNT = 1 shl 20 // ~1M chunks, ~1 TiB

    /** Recomputes the full-file digest and compares to [reference] —
     *  cheap size check first, then the hash. Touches no key
     *  material. */
    fun verify(sealedFile: File, reference: SnapshotReference): Boolean {
        if (sealedFile.length() != reference.sealedByteSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        sealedFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().toLowercaseHex() == reference.digest
    }

    /** @throws BackupError.IncompleteSnapshot on any verification,
     *    framing, or authentication failure.
     *  @throws BackupError.UnsupportedProfile on an unrecognised suite
     *    byte — refuses to guess at a future suite rather than
     *    misinterpreting it. */
    fun open(
        sealedFile: File,
        reference: SnapshotReference,
        archiveRoot: ByteArray,
        destinationFile: File,
    ) {
        if (!verify(sealedFile, reference)) throw BackupError.IncompleteSnapshot

        sealedFile.inputStream().buffered().use { input ->
            val header = ByteArray(BackupSealer.HEADER_BYTES)
            readFully(input, header)
            val buffer = ByteBuffer.wrap(header)
            val magic = ByteArray(9).also { buffer.get(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw BackupError.IncompleteSnapshot
            val suite = buffer.get()
            buffer.get() // reserved
            val chunkPlainBytes = buffer.int
            val chunkCount = buffer.int.toLong() and 0xFFFFFFFFL
            val snapshotSalt = ByteArray(32).also { buffer.get(it) }

            if (suite != BackupSealer.SUITE_AES_256_GCM) throw BackupError.UnsupportedProfile
            if (chunkPlainBytes <= 0 || chunkPlainBytes > BackupSealer.CHUNK_PLAIN_BYTES) {
                throw BackupError.IncompleteSnapshot
            }
            if (chunkCount < 0 || chunkCount > MAX_CHUNK_COUNT) throw BackupError.IncompleteSnapshot

            val snapshotKey = BackupKeys.snapshotKey(archiveRoot, snapshotSalt)
            val cipherKey = SecretKeySpec(snapshotKey, "AES")

            BufferedOutputStream(FileOutputStream(destinationFile)).use { out ->
                for (index in 0 until chunkCount) {
                    val nonce = ByteArray(NONCE_BYTES)
                    readFully(input, nonce)
                    val expectedNonce = BackupSealer.nonceForChunk(index)
                    if (!nonce.contentEquals(expectedNonce)) throw BackupError.IncompleteSnapshot

                    // Every chunk but the last is exactly chunkPlainBytes
                    // (+ tag); reading is bounded by chunkPlainBytes so a
                    // truncated final chunk fails cleanly rather than
                    // over-reading into the next chunk's bytes.
                    val combined = ByteArray(chunkPlainBytes + TAG_BYTES)
                    val readCount = readAtMost(input, combined)
                    val trimmed = if (readCount == combined.size) combined else combined.copyOf(readCount)
                    if (trimmed.size <= TAG_BYTES) throw BackupError.IncompleteSnapshot

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, cipherKey, GCMParameterSpec(TAG_BYTES * 8, nonce))
                    val plain = try {
                        cipher.doFinal(trimmed)
                    } catch (_: AEADBadTagException) {
                        throw BackupError.IncompleteSnapshot
                    }
                    out.write(plain)
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val read = input.read(into, offset, into.size - offset)
            if (read < 0) throw BackupError.IncompleteSnapshot
            offset += read
        }
    }

    /** Reads up to `into.size` bytes, returning fewer only at EOF
     *  (unlike [readFully], a short read here is a legitimate "this
     *  was the last, short, chunk" rather than an error — the caller
     *  distinguishes truncation from an intentionally short final
     *  chunk by comparing against the expected plain size). */
    private fun readAtMost(input: java.io.InputStream, into: ByteArray): Int {
        var offset = 0
        while (offset < into.size) {
            val read = input.read(into, offset, into.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
