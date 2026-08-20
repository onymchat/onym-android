package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

/**
 * The plaintext archive container framed inside a sealed snapshot —
 * one layer below `BackupSealer`, which doesn't know this format
 * exists.
 *
 * ```
 * "ONYMBAK1"(8) + headerLength:u32 BE + header JSON (see [BackupArchiveHeader])
 * then records: [kind:1 byte][length:u32 BE][bytes], one per header.entries, fixed order:
 *   groups(0x01) -> messages(0x02) -> invitations(0x03) -> consents(0x04) -> blobCiphertext(0x05)
 * ```
 *
 * Mirrors `BackupArchive.swift` in onym-ios OnymBackup.
 */
enum class BackupArchiveEntryKind(val byte: Byte) {
    Groups(0x01),
    Messages(0x02),
    Invitations(0x03),
    Consents(0x04),
    BlobCiphertext(0x05),
    ;

    companion object {
        fun fromByte(value: Byte): BackupArchiveEntryKind? = entries.firstOrNull { it.byte == value }
    }
}

@Serializable
data class BackupArchiveEntry(val kind: Int, val length: Long, val sha256: String)

@Serializable
data class BackupArchiveHeader(
    val archiveVersion: Int = 1,
    val createdAtEpochSeconds: Long,
    val identityCount: Int,
    /** The true, unpadded plaintext length — recorded here, not by
     *  `BackupSealer`, so `SnapshotReference.sealedByteSize` stays a
     *  coarse padded bucket while this stays the honest number a
     *  restore can reason about. */
    val contentByteSize: Long,
    val mediaPolicy: String,
    val entries: List<BackupArchiveEntry>,
)

private const val MAGIC = "ONYMBAK1"
private const val MAGIC_BYTES = 8
private const val LENGTH_PREFIX_BYTES = 4

/**
 * Streams records to a scratch file first, then copies them behind a
 * finished header into the final plaintext archive file — avoids
 * buffering hundreds of MB in memory. The scratch file holds
 * plaintext and must be removed however writing ends; [abort] does
 * this for the non-happy path, [finish] for the happy one.
 */
class BackupArchiveWriter(private val scratchFile: File) {
    private val entries = mutableListOf<BackupArchiveEntry>()
    private var identityCount = 0
    private var mediaPolicy = BackupMediaPolicy.DescriptorsOnly
    private var finished = false
    private val out = BufferedOutputStream(FileOutputStream(scratchFile))

    fun setIdentityCount(count: Int) {
        identityCount = count
    }

    fun setMediaPolicy(policy: BackupMediaPolicy) {
        mediaPolicy = policy
    }

    fun appendGroups(records: List<BackupGroupRecord>) =
        appendJson(BackupArchiveEntryKind.Groups, records, ListSerializer(BackupGroupRecord.serializer()))

    fun appendMessages(records: List<BackupMessageRecord>) =
        appendJson(BackupArchiveEntryKind.Messages, records, ListSerializer(BackupMessageRecord.serializer()))

    fun appendInvitations(records: List<BackupInvitationRecord>) =
        appendJson(BackupArchiveEntryKind.Invitations, records, ListSerializer(BackupInvitationRecord.serializer()))

    fun appendConsents(records: List<BackupConsentRecord>) =
        appendJson(BackupArchiveEntryKind.Consents, records, ListSerializer(BackupConsentRecord.serializer()))

    fun appendBlob(record: BackupBlobRecord) {
        appendRaw(BackupArchiveEntryKind.BlobCiphertext, encodeBlob(record))
    }

    private fun <T> appendJson(kind: BackupArchiveEntryKind, records: List<T>, serializer: kotlinx.serialization.KSerializer<List<T>>) {
        if (records.isEmpty()) return
        appendRaw(kind, Json.encodeToString(serializer, records).toByteArray(Charsets.UTF_8))
    }

    private fun appendRaw(kind: BackupArchiveEntryKind, bytes: ByteArray) {
        if (finished) throw BackupError.LocalFailure(LocalFailureReason.ArchiveWriterFinished)
        out.write(kind.byte.toInt())
        out.write(ByteBuffer.allocate(LENGTH_PREFIX_BYTES).putInt(bytes.size).array())
        out.write(bytes)
        entries += BackupArchiveEntry(
            kind = kind.byte.toInt(),
            length = bytes.size.toLong(),
            sha256 = BackupFormat.digest(bytes),
        )
    }

    /** Writes the finished container to [destinationFile]. Idempotent
     *  guard: calling this (or [abort]) twice throws
     *  [LocalFailureReason.ArchiveWriterFinished] rather than
     *  masquerading as corruption. */
    fun finish(destinationFile: File, createdAt: Instant): File {
        if (finished) throw BackupError.LocalFailure(LocalFailureReason.ArchiveWriterFinished)
        finished = true
        out.close()

        val header = BackupArchiveHeader(
            createdAtEpochSeconds = createdAt.epochSecond,
            identityCount = identityCount,
            contentByteSize = scratchFile.length(),
            mediaPolicy = mediaPolicy.name,
            entries = entries,
        )
        val headerBytes = Json.encodeToString(BackupArchiveHeader.serializer(), header).toByteArray(Charsets.UTF_8)

        try {
            BufferedOutputStream(FileOutputStream(destinationFile)).use { fout ->
                fout.write(MAGIC.toByteArray(Charsets.US_ASCII))
                fout.write(ByteBuffer.allocate(LENGTH_PREFIX_BYTES).putInt(headerBytes.size).array())
                fout.write(headerBytes)
                scratchFile.inputStream().use { it.copyTo(fout) }
            }
        } finally {
            scratchFile.delete()
        }
        return destinationFile
    }

    /** Discards everything written so far — the scratch file is
     *  plaintext and must not survive a failed compose. */
    fun abort() {
        if (!finished) {
            finished = true
            out.close()
        }
        scratchFile.delete()
    }

    private fun encodeBlob(record: BackupBlobRecord): ByteArray {
        val shaBytes = record.sha256.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(2).putShort(shaBytes.size.toShort()).array())
        out.write(shaBytes)
        out.write(record.ciphertext)
        return out.toByteArray()
    }
}

/** Reads records back, verifying framing and per-record digests
 *  before any bytes reach the caller. */
class BackupArchiveReader(file: File) {
    val header: BackupArchiveHeader
    private val recordsInput: InputStream
    private val rawStream: InputStream

    init {
        rawStream = file.inputStream().buffered()
        val magic = ByteArray(MAGIC_BYTES)
        readFully(rawStream, magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw BackupError.IncompleteSnapshot
        val lengthBytes = ByteArray(LENGTH_PREFIX_BYTES)
        readFully(rawStream, lengthBytes)
        val headerLength = ByteBuffer.wrap(lengthBytes).int
        if (headerLength <= 0) throw BackupError.IncompleteSnapshot
        val headerBytes = ByteArray(headerLength)
        readFully(rawStream, headerBytes)
        header = try {
            Json.decodeFromString(BackupArchiveHeader.serializer(), String(headerBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            throw BackupError.IncompleteSnapshot
        }
        recordsInput = rawStream
    }

    /** Decodes every record in [header]'s declared order, verifying
     *  the framing kind byte against the header's declared kind
     *  (neither the archive digest nor the outer AEAD covers the
     *  framing byte itself — the header is authoritative), the
     *  declared length, and the per-record SHA-256 — all **before**
     *  [action] ever sees the bytes. Throws
     *  [BackupError.IncompleteSnapshot] on a repeated kind (silently
     *  overwriting half a person's rows is exactly the bug this
     *  guards against) or any mismatch. */
    fun forEachRecord(action: (BackupArchiveEntryKind, ByteArray) -> Unit) {
        val seenKinds = mutableSetOf<Int>()
        for (entry in header.entries) {
            if (!seenKinds.add(entry.kind)) throw BackupError.IncompleteSnapshot
            val kindByte = recordsInput.read()
            if (kindByte < 0 || kindByte.toByte() != entry.kind.toByte()) throw BackupError.IncompleteSnapshot
            val lengthBytes = ByteArray(LENGTH_PREFIX_BYTES)
            readFully(recordsInput, lengthBytes)
            val length = ByteBuffer.wrap(lengthBytes).int
            if (length.toLong() != entry.length) throw BackupError.IncompleteSnapshot
            val bytes = ByteArray(length)
            readFully(recordsInput, bytes)
            if (BackupFormat.digest(bytes) != entry.sha256) throw BackupError.IncompleteSnapshot
            val kind = BackupArchiveEntryKind.fromByte(entry.kind.toByte()) ?: throw BackupError.IncompleteSnapshot
            action(kind, bytes)
        }
    }

    fun close() = rawStream.close()

    companion object {
        fun decodeGroups(bytes: ByteArray): List<BackupGroupRecord> =
            Json.decodeFromString(ListSerializer(BackupGroupRecord.serializer()), String(bytes, Charsets.UTF_8))

        fun decodeMessages(bytes: ByteArray): List<BackupMessageRecord> =
            Json.decodeFromString(ListSerializer(BackupMessageRecord.serializer()), String(bytes, Charsets.UTF_8))

        fun decodeInvitations(bytes: ByteArray): List<BackupInvitationRecord> =
            Json.decodeFromString(ListSerializer(BackupInvitationRecord.serializer()), String(bytes, Charsets.UTF_8))

        fun decodeConsents(bytes: ByteArray): List<BackupConsentRecord> =
            Json.decodeFromString(ListSerializer(BackupConsentRecord.serializer()), String(bytes, Charsets.UTF_8))

        fun decodeBlob(bytes: ByteArray): BackupBlobRecord {
            if (bytes.size < 2) throw BackupError.IncompleteSnapshot
            val buffer = ByteBuffer.wrap(bytes)
            val shaLength = buffer.short.toInt()
            if (shaLength < 0 || 2 + shaLength > bytes.size) throw BackupError.IncompleteSnapshot
            val sha = String(bytes, 2, shaLength, Charsets.UTF_8)
            val ciphertext = bytes.copyOfRange(2 + shaLength, bytes.size)
            return BackupBlobRecord(sha256 = sha, ciphertext = ciphertext)
        }
    }
}

private fun readFully(input: InputStream, into: ByteArray) {
    var offset = 0
    while (offset < into.size) {
        val read = input.read(into, offset, into.size - offset)
        if (read < 0) throw BackupError.IncompleteSnapshot
        offset += read
    }
}
