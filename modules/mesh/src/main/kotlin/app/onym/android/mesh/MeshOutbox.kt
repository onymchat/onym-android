package app.onym.android.mesh

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * What this device broadcasts: sealed envelopes as compact frames,
 * looped for 24 hours, because nothing on the air ever confirms a
 * delivery — repetition is the only reliability (spec §3).
 *
 * The tag is fixed at enqueue time for that epoch; listeners accept
 * epochs up to [MeshInboxTransport.RETENTION] old, so a frame stays
 * matchable for as long as it loops. [next] interleaves envelopes, so a
 * long one doesn't hold back the rest.
 */
class MeshOutbox(
    private val now: () -> Instant = Instant::now,
    private val retention: Duration = MeshInboxTransport.RETENTION,
    private val maxEnvelopes: Int = 64,
    private val maxChunk: Int = MeshFrameCodec.COMPACT_MAX_CHUNK,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(val frames: List<ByteArray>, val expires: Instant) {
        var cursor = 0
    }

    private val entries = ArrayList<Entry>()
    private var turn = 0

    /** False when the envelope doesn't fit the compact format (255 × 15 bytes). */
    @Synchronized
    fun enqueue(envelope: ByteArray, pairKey: ByteArray): Boolean {
        val at = now()
        val epoch = MeshEpoch.number(at)
        val frames = MeshFrameCodec.fragmentCompact(
            envelope = envelope,
            tag = MeshTag.derive(pairKey, epoch),
            epoch = epoch,
            envelopeId = random.nextInt(0x1_0000),
            maxChunk = maxChunk,
        ) ?: return false
        expire(at)
        if (entries.size >= maxEnvelopes) entries.removeAt(0)
        entries.add(Entry(frames, at.plus(retention)))
        return true
    }

    /** The next frame to put on the air, or null when there's nothing to send. */
    @Synchronized
    fun next(): ByteArray? {
        expire(now())
        if (entries.isEmpty()) return null
        turn %= entries.size
        val entry = entries[turn]
        turn++
        val frame = entry.frames[entry.cursor % entry.frames.size]
        entry.cursor++
        return frame
    }

    @get:Synchronized
    val size: Int get() = entries.size

    private fun expire(at: Instant) {
        entries.removeAll { !at.isBefore(it.expires) }
    }
}
