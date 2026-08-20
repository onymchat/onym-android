package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed-container byte layout and the chunked-AEAD sealing
 * algorithm (onym-system `backup/UI-Backup-Object-HTTP.md` §5.4).
 * Content-agnostic: seals whatever bytes [plaintextFile] holds — it
 * never parses the archive format layered on top by
 * `BackupComposer`/`BackupArchive`.
 *
 * ```
 * "ONYMSEAL1"          9 bytes ASCII magic
 * u8   suiteId         0x01
 * u8   reserved        0x00
 * u32  chunkPlainBytes big-endian, fixed 1_048_576 (1 MiB)
 * u32  chunkCount      big-endian
 * byte snapshotSalt    32 bytes
 * repeat chunkCount:   AES-256-GCM combined chunk = nonce(12) ‖ ciphertext ‖ tag(16)
 * ```
 *
 * The plaintext is Padmé-padded (zero bytes) up to the next bucket
 * before chunking, so `sealedByteSize` is a coarse signal rather than
 * a measurement of the true content length recorded elsewhere (the
 * archive's own header, one layer up).
 *
 * Mirrors `BackupSealer` in onym-ios OnymBackup.
 */
object BackupSealer {
    const val CHUNK_PLAIN_BYTES = 1_048_576 // 1 MiB, fixed
    const val SUITE_AES_256_GCM: Byte = 0x01
    private const val MAGIC = "ONYMSEAL1"
    private const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    const val HEADER_BYTES = 9 + 1 + 1 + 4 + 4 + 32 // 51

    /** Seals [plaintextFile] to [destinationFile] under a key derived
     *  from [archiveRoot]. Deletes [destinationFile] first if sealing
     *  fails partway, so a failed seal never leaves an orphan
     *  ciphertext file. */
    fun seal(plaintextFile: File, destinationFile: File, archiveRoot: ByteArray): SnapshotReference =
        seal(plaintextFile, destinationFile, archiveRoot, snapshotSalt = randomSalt())

    /** Salt is internal-only in the public API on purpose: reusing a
     *  salt reproduces the same (key, nonce) sequence and breaks both
     *  confidentiality and integrity if two different plaintexts are
     *  ever sealed under it. This overload exists solely for tests
     *  that need a pinned salt to assert the worked example / fixture
     *  bytes. */
    internal fun seal(
        plaintextFile: File,
        destinationFile: File,
        archiveRoot: ByteArray,
        snapshotSalt: ByteArray,
    ): SnapshotReference {
        require(snapshotSalt.size == 32) { "snapshotSalt must be 32 bytes" }
        try {
            val trueLength = plaintextFile.length()
            val paddedLength = BackupPadding.paddedLength(trueLength)
            val chunkCount = if (paddedLength == 0L) {
                0L
            } else {
                (paddedLength + CHUNK_PLAIN_BYTES - 1) / CHUNK_PLAIN_BYTES
            }
            val snapshotKey = BackupKeys.snapshotKey(archiveRoot, snapshotSalt)
            val cipherKey = SecretKeySpec(snapshotKey, "AES")

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytesWritten = 0L
            DigestOutputStream(BufferedOutputStream(FileOutputStream(destinationFile)), digest).use { out ->
                val header = ByteBuffer.allocate(HEADER_BYTES)
                header.put(MAGIC.toByteArray(Charsets.US_ASCII))
                header.put(SUITE_AES_256_GCM)
                header.put(0x00.toByte())
                header.putInt(CHUNK_PLAIN_BYTES)
                header.putInt(chunkCount.toIntExactOrThrow())
                header.put(snapshotSalt)
                out.write(header.array())
                totalBytesWritten += HEADER_BYTES

                plaintextFile.inputStream().buffered().use { plainIn ->
                    var producedPlainBytes = 0L
                    var chunkIndex = 0L
                    while (chunkIndex < chunkCount) {
                        val remainingTotal = paddedLength - producedPlainBytes
                        val thisChunkSize = minOf(CHUNK_PLAIN_BYTES.toLong(), remainingTotal).toInt()
                        val chunkPlain = ByteArray(thisChunkSize)
                        var filled = 0
                        while (filled < thisChunkSize) {
                            val read = plainIn.read(chunkPlain, filled, thisChunkSize - filled)
                            if (read <= 0) break // exhausted real content — rest stays zero (padding)
                            filled += read
                        }
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        val nonce = nonceForChunk(chunkIndex)
                        cipher.init(Cipher.ENCRYPT_MODE, cipherKey, GCMParameterSpec(TAG_BYTES * 8, nonce))
                        val combined = cipher.doFinal(chunkPlain)
                        out.write(nonce)
                        out.write(combined)
                        totalBytesWritten += NONCE_BYTES + combined.size
                        producedPlainBytes += thisChunkSize
                        chunkIndex++
                    }
                }
            }

            return SnapshotReference(
                digest = "sha256:" + digest.digest().toLowercaseHex(),
                sealedByteSize = totalBytesWritten,
            )
        } catch (e: Exception) {
            destinationFile.delete()
            throw e
        }
    }

    /** 12 bytes = 4 zero bytes ‖ big-endian u64(index). Safe by
     *  construction under a per-snapshot key — no birthday argument
     *  needed, unlike a random nonce. */
    internal fun nonceForChunk(index: Long): ByteArray {
        val buffer = ByteBuffer.allocate(NONCE_BYTES)
        buffer.putInt(0, 0)
        buffer.putLong(4, index)
        return buffer.array()
    }

    private fun randomSalt(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun Long.toIntExactOrThrow(): Int {
        if (this < 0 || this > Int.MAX_VALUE) throw BackupError.LocalFailure(LocalFailureReason.KeyMaterialInvalid)
        return toInt()
    }
}
