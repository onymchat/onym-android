package app.onym.android.mesh

import java.time.Duration
import java.time.Instant

/**
 * One fragment of a sealed envelope, in the shared shape reassembly and
 * delivery work on. On the air it is either a v1 frame (extended
 * advertising, 18-byte header; spec §4) or a compact frame (legacy
 * advertising, 9-byte header; §4b) — see [MeshFrameCodec].
 *
 * [envelopeId] is v1's 64-bit ID as is; a compact frame's 16-bit ID is
 * folded together with its epoch and tag, which is what scopes it.
 */
class MeshFrame(
    val epoch: Long,
    val tag: Int,
    val envelopeId: Long,
    val index: Int,
    val count: Int,
    val chunk: ByteArray,
)

object MeshFrameCodec {
    const val V1_VERSION: Byte = 0x01
    const val V1_HEADER = 18

    const val COMPACT_KIND: Byte = 0xC1.toByte()
    const val COMPACT_HEADER = 9
    /** Bluetooth SIG company ID for internal testing, until Onym has its own. */
    const val COMPANY_ID = 0xFFFF
    /**
     * Bytes of envelope per compact frame: 31, less the AD header and
     * company ID (4), less a Flags AD (3) some stacks add even when not
     * connectable, less the header (9).
     */
    const val COMPACT_MAX_CHUNK = 15

    /** The largest envelope a v1 frame set carries. */
    const val MAX_ENVELOPE = 16 * 1024

    // ─── v1 ─────────────────────────────────────────────────────────

    fun decodeV1(b: ByteArray): MeshFrame? {
        if (b.size <= V1_HEADER || b[0] != V1_VERSION) return null
        val epoch = readUInt(b, 1, 4)
        val tag = MeshTag.fromBytes(b, 5)
        val id = readUInt(b, 8, 8)
        val index = b[16].toInt() and 0xFF
        val count = b[17].toInt() and 0xFF
        if (count < 1 || index >= count) return null
        return MeshFrame(epoch, tag, id, index, count, b.copyOfRange(V1_HEADER, b.size))
    }

    fun encodeV1(f: MeshFrame): ByteArray =
        byteArrayOf(V1_VERSION) + uInt(f.epoch, 4) + MeshTag.toBytes(f.tag) + uInt(f.envelopeId, 8) +
            byteArrayOf(f.index.toByte(), f.count.toByte()) + f.chunk

    fun fragmentV1(envelope: ByteArray, tag: Int, epoch: Long, envelopeId: Long, maxFrameSize: Int): List<ByteArray>? {
        val chunkSize = maxFrameSize - V1_HEADER
        if (chunkSize <= 0 || envelope.isEmpty() || envelope.size > MAX_ENVELOPE) return null
        return chunks(envelope, chunkSize)?.let { parts ->
            parts.mapIndexed { i, c -> encodeV1(MeshFrame(epoch, tag, envelopeId, i, parts.size, c)) }
        }
    }

    // ─── compact ────────────────────────────────────────────────────

    /** Header fields of a compact frame, before its epoch is resolved. */
    class Compact(val epochLow: Int, val tag: Int, val envelopeId: Int, val index: Int, val count: Int, val chunk: ByteArray) {
        /** As a shared frame, once the receiver has resolved the epoch. */
        fun toFrame(epoch: Long): MeshFrame {
            val key = ((epoch and 0xFF_FFFF) shl 40) or (tag.toLong() shl 16) or envelopeId.toLong()
            return MeshFrame(epoch, tag, key, index, count, chunk)
        }
    }

    /** From the payload after the company ID. */
    fun decodeCompact(b: ByteArray): Compact? {
        if (b.size <= COMPACT_HEADER || b[0] != COMPACT_KIND) return null
        val index = b[7].toInt() and 0xFF
        val count = b[8].toInt() and 0xFF
        if (count < 1 || index >= count) return null
        return Compact(
            epochLow = b[1].toInt() and 0xFF,
            tag = MeshTag.fromBytes(b, 2),
            envelopeId = (b[5].toInt() and 0xFF shl 8) or (b[6].toInt() and 0xFF),
            index = index, count = count,
            chunk = b.copyOfRange(COMPACT_HEADER, b.size),
        )
    }

    fun fragmentCompact(
        envelope: ByteArray,
        tag: Int,
        epoch: Long,
        envelopeId: Int,
        maxChunk: Int = COMPACT_MAX_CHUNK,
    ): List<ByteArray>? {
        if (maxChunk <= 0 || envelope.isEmpty()) return null
        return chunks(envelope, maxChunk)?.let { parts ->
            parts.mapIndexed { i, c ->
                byteArrayOf(COMPACT_KIND, (epoch and 0xFF).toByte()) + MeshTag.toBytes(tag) +
                    byteArrayOf((envelopeId ushr 8).toByte(), envelopeId.toByte(), i.toByte(), parts.size.toByte()) + c
            }
        }
    }

    /**
     * The full epoch a compact frame's low byte names, if one in the
     * receiver's window (`oldest..current + 1`, 98 epochs < 256) has it.
     */
    fun resolveEpoch(low: Int, oldest: Long, current: Long): Long? {
        var e = current + 1
        while (e >= oldest) {
            if ((e and 0xFF).toInt() == low) return e
            e--
        }
        return null
    }

    // ─── helpers ────────────────────────────────────────────────────

    private fun chunks(envelope: ByteArray, size: Int): List<ByteArray>? {
        val count = (envelope.size + size - 1) / size
        if (count > 255) return null
        return (0 until count).map { i -> envelope.copyOfRange(i * size, minOf((i + 1) * size, envelope.size)) }
    }

    private fun readUInt(b: ByteArray, offset: Int, length: Int): Long {
        var v = 0L
        for (i in 0 until length) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    private fun uInt(value: Long, length: Int): ByteArray =
        ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }
}

/**
 * Puts envelopes back together from fragments arriving in any order, any
 * number of times, interleaved with other envelopes. Broadcasters loop, so
 * every limit here is a cap rather than an error: memory and time stay
 * bounded however hostile the air is, and an envelope that can't be
 * completed is simply forgotten. Not thread-safe; one collector owns it.
 */
class MeshReassembler(
    private val timeout: Duration = Duration.ofMinutes(10),
    private val maxPending: Int = 64,
    /** Delivered envelopes are ignored this long, so a looping broadcaster doesn't redeliver each lap. */
    private val deliveredMemory: Duration = Duration.ofMinutes(30),
) {
    private class Pending(val count: Int, val firstSeen: Instant) {
        val chunks = HashMap<Int, ByteArray>()
        var bytes = 0
    }

    private val pending = HashMap<Long, Pending>()
    private val delivered = HashMap<Long, Instant>()

    fun add(frame: MeshFrame, now: Instant): ByteArray? {
        pending.entries.removeIf { Duration.between(it.value.firstSeen, now) >= timeout }
        delivered.entries.removeIf { Duration.between(it.value, now) >= deliveredMemory }
        if (delivered.containsKey(frame.envelopeId)) return null

        val existing = pending[frame.envelopeId]
        val entry = existing ?: Pending(frame.count, now)
        // The first fragment sets the envelope's shape; one that disagrees is noise.
        if (entry.count != frame.count) return null
        if (!entry.chunks.containsKey(frame.index)) {
            if (entry.bytes + frame.chunk.size > MeshFrameCodec.MAX_ENVELOPE) {
                pending.remove(frame.envelopeId)
                return null
            }
            entry.chunks[frame.index] = frame.chunk
            entry.bytes += frame.chunk.size
        }
        if (entry.chunks.size < entry.count) {
            if (existing == null) {
                if (pending.size >= maxPending) {
                    pending.minByOrNull { it.value.firstSeen }?.let { pending.remove(it.key) }
                }
                pending[frame.envelopeId] = entry
            }
            return null
        }
        pending.remove(frame.envelopeId)
        delivered[frame.envelopeId] = now
        val out = java.io.ByteArrayOutputStream(entry.bytes)
        for (i in 0 until entry.count) out.write(entry.chunks.getValue(i))
        return out.toByteArray()
    }
}
