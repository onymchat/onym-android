package app.onym.android.mesh

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * What this device broadcasts: binary sealed envelopes as coded frames
 * (onym-ios docs/mesh-ble-v1.md §4c), looped for 24 hours — nothing on the
 * air ever confirms a delivery, so repetition is the only reliability.
 *
 * Each envelope sends its K source frames, then an unending run of fresh
 * repair frames: a listener needs any K of them, so every frame it catches
 * counts, and it never waits on one particular missing piece.
 *
 * The newest envelope gets three of every four turns while it is under
 * [freshFor] old — a message just sent should cross the room in seconds,
 * not wait behind a day's backlog taking equal turns.
 *
 * The tag is fixed at enqueue time; listeners accept epochs up to
 * [MeshInboxTransport.RETENTION] old, so a frame stays matchable for as
 * long as it loops.
 */
class MeshOutbox(
    private val now: () -> Instant = Instant::now,
    private val retention: Duration = MeshInboxTransport.RETENTION,
    private val maxEnvelopes: Int = 64,
    private val freshFor: Duration = Duration.ofSeconds(60),
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(
        val encoder: MeshFountain.Encoder,
        val epoch: Long,
        val tag: Int,
        val queuedAt: Instant,
        val expires: Instant,
    ) {
        var next = 0
    }

    private val entries = ArrayList<Entry>()
    private var turn = 0
    private var slot = 0

    /** False when the envelope doesn't fit 255 coded symbols. */
    @Synchronized
    fun enqueue(envelope: ByteArray, pairKey: ByteArray): Boolean {
        val at = now()
        val epoch = MeshEpoch.number(at)
        val encoder = MeshFountain.Encoder.of(envelope, random.nextInt(0x1_0000)) ?: return false
        expire(at)
        if (entries.size >= maxEnvelopes) entries.removeAt(0)
        entries.add(Entry(encoder, epoch, MeshTag.derive(pairKey, epoch), at, at.plus(retention)))
        return true
    }

    /** The next coded frame to put on the air, or null with nothing queued. */
    @Synchronized
    fun next(): ByteArray? {
        val at = now()
        expire(at)
        if (entries.isEmpty()) return null
        val newest = entries.last()
        val fresh = Duration.between(newest.queuedAt, at) < freshFor
        val entry = if (fresh && entries.size > 1 && slot++ % 4 != 3) {
            newest
        } else {
            turn %= entries.size
            entries[turn++]
        }
        val index = entry.next
        // Source frames once, then fresh repair indices; wrap past the
        // 16-bit space back to the first repair index.
        entry.next = if (index >= 0xFFFF) entry.encoder.k else index + 1
        return MeshCodedFrame(
            epochLow = (entry.epoch and 0xFF).toInt(),
            tag = entry.tag,
            envelopeId = entry.encoder.envelopeId,
            k = entry.encoder.k,
            index = index,
            symbol = entry.encoder.symbol(index),
        ).encode()
    }

    @get:Synchronized
    val size: Int get() = entries.size

    private fun expire(at: Instant) {
        entries.removeAll { !at.isBefore(it.expires) }
    }
}
