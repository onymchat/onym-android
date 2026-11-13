package app.onym.android.mesh

import app.onym.android.transport.InboundDelivery
import app.onym.android.transport.InboundInbox
import app.onym.android.transport.TransportError
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MeshTransportAndOutboxTest {
    private val inbox = TransportInboxId("aabbccddeeff0011")
    private val pairKey = ByteArray(32) { 0x42 }
    private val now = Instant.parse("2026-11-11T12:00:00Z")

    // ─── receive-only transport ─────────────────────────────────────

    private fun kotlinx.coroutines.test.TestScope.transport(key: ByteArray = pairKey) =
        MeshInboxTransport(
            frames = emptyFlow(),
            contacts = { listOf(MeshContact(inbox, key)) },
            scope = this,
            now = { now },
        )

    /** What the inbox subscription yields after feeding [input]. */
    private suspend fun kotlinx.coroutines.test.TestScope.received(
        t: MeshInboxTransport,
        input: List<ByteArray>,
        expect: Int,
    ): List<InboundInbox> {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1_000) { t.subscribe(inbox).take(expect).toList() } ?: emptyList()
        }
        for (bytes in input) t.receive(bytes)
        return collected.await()
    }

    @Test fun deliversCompactFramesMarkedBluetooth() = runTest {
        val envelope = ByteArray(100) { 7 }
        val epoch = MeshEpoch.number(now)
        val frames = MeshFrameCodec.fragmentCompact(envelope, MeshTag.derive(pairKey, epoch), epoch, 0x0102)!!
        val out = received(transport(), frames.reversed(), expect = 1)
        assertEquals(1, out.size)
        assertArrayEquals(envelope, out[0].payload)
        assertEquals("no receipt may go back for it", InboundDelivery.BLUETOOTH, out[0].delivery)
    }

    @Test fun deliversV1Frames() = runTest {
        val envelope = ByteArray(60) { 3 }
        val epoch = MeshEpoch.number(now)
        val frames = MeshFrameCodec.fragmentV1(envelope, MeshTag.derive(pairKey, epoch), epoch, 1L, maxFrameSize = 40)!!
        val out = received(transport(), frames, expect = 1)
        assertArrayEquals(envelope, out.single().payload)
        assertEquals("ble:0000000000000001", out.single().messageId)
    }

    @Test fun dropsFramesForSomeoneElse() = runTest {
        val epoch = MeshEpoch.number(now)
        val stranger = ByteArray(32) { 0x99.toByte() }
        val frames = MeshFrameCodec.fragmentCompact(byteArrayOf(1, 2, 3), MeshTag.derive(stranger, epoch), epoch, 1)!!
        assertTrue(received(transport(), frames, expect = 1).isEmpty())
    }

    @Test fun acceptsTheDayWindowAndRejectsBeyondIt() = runTest {
        val current = MeshEpoch.number(now)
        val dayOld = MeshEpoch.number(now.minus(MeshInboxTransport.RETENTION))
        fun v1(epoch: Long, id: Long) = MeshFrameCodec.fragmentV1(byteArrayOf(id.toByte()), MeshTag.derive(pairKey, epoch), epoch, id, 40)!!
        val accepted = received(transport(), v1(dayOld, 1) + v1(current + 1, 2), expect = 2)
        assertEquals(2, accepted.size)
        val rejected = received(transport(), v1(dayOld - 1, 3) + v1(current + 2, 4), expect = 1)
        assertTrue(rejected.isEmpty())
    }

    @Test fun neverSends() = runTest {
        try {
            transport().send(byteArrayOf(1), inbox)
            fail("the mesh transport is receive-only")
        } catch (_: TransportError.NotConnected) {
        }
    }

    @Test fun ignoresGarbage() = runTest {
        assertTrue(received(transport(), listOf(ByteArray(0), byteArrayOf(1, 2, 3), ByteArray(64) { -1 }), expect = 1).isEmpty())
    }

    // ─── outbox ─────────────────────────────────────────────────────

    /** The outbox's coded frames are what a listener rebuilds: any k of
     *  them, here only repair frames after skipping the sources. */
    @Test fun outboxFramesDecodeThroughAListener() = runTest {
        val envelope = binaryEnvelope(ciphertextSize = 300)
        val outbox = MeshOutbox(now = { now })
        assertTrue(outbox.enqueue(envelope, pairKey))
        val k = MeshFountain.Encoder.of(envelope, 0)!!.k
        val stream = (0 until 3 * k).map { outbox.next()!! }
        stream.forEach { assertEquals("a full legacy advertisement", MeshCodedFrame.SIZE, it.size) }
        val out = received(transport(), stream.drop(k), expect = 1)
        assertArrayEquals(MeshEnvelope.jsonFromBinary(envelope), out.single().payload)
        assertEquals(InboundDelivery.BLUETOOTH, out.single().delivery)
    }

    /** Sources once, then fresh repair indices — never the same frame twice. */
    @Test fun outboxSendsSourcesThenFreshRepairs() {
        val outbox = MeshOutbox(now = { now })
        outbox.enqueue(ByteArray(40) { 1 }, pairKey)
        val indices = (0 until 10).map { MeshCodedFrame.decode(outbox.next()!!)!!.index }
        assertEquals((0 until 10).toList(), indices)
    }

    /** A message just sent gets three of every four turns while it's fresh. */
    @Test fun outboxGivesTheNewestMessageMostOfTheAir() {
        var clock = now
        val outbox = MeshOutbox(now = { clock })
        outbox.enqueue(ByteArray(40) { 1 }, pairKey)
        clock = now.plusSeconds(1)
        outbox.enqueue(ByteArray(40) { 2 }, pairKey)
        val ids = (0 until 40).map { MeshCodedFrame.decode(outbox.next()!!)!!.envelopeId }
        val newest = ids.groupingBy { it }.eachCount().maxBy { it.value }
        assertTrue("newest got ${newest.value} of 40", newest.value >= 28)

        // Once it isn't fresh any more, turns are shared again.
        clock = now.plusSeconds(120)
        val later = (0 until 40).map { MeshCodedFrame.decode(outbox.next()!!)!!.envelopeId }
        assertEquals(setOf(20), later.groupingBy { it }.eachCount().values.toSet())
    }

    @Test fun outboxForgetsAfterRetention() {
        var clock = now
        val outbox = MeshOutbox(now = { clock })
        outbox.enqueue(byteArrayOf(1), pairKey)
        clock = now.plus(MeshInboxTransport.RETENTION).minus(Duration.ofSeconds(1))
        assertTrue(outbox.next() != null)
        clock = now.plus(MeshInboxTransport.RETENTION)
        assertNull(outbox.next())
    }

    @Test fun outboxRefusesWhatCodedFramesCantCarry() {
        val outbox = MeshOutbox(now = { now })
        assertFalse(outbox.enqueue(ByteArray(MeshFountain.MAX_ENVELOPE + 1), pairKey))
        assertFalse(outbox.enqueue(ByteArray(0), pairKey))
        assertEquals(0, outbox.size)
    }

    /** A plausible binary sealed envelope: format byte, fixed fields, ciphertext. */
    private fun binaryEnvelope(ciphertextSize: Int): ByteArray =
        byteArrayOf(1) + ByteArray(156) { (it * 7).toByte() } + ByteArray(ciphertextSize) { (it * 13).toByte() }

    // ─── codec edges ────────────────────────────────────────────────

    @Test fun rejectsMalformedFrames() {
        val good = MeshFrameCodec.fragmentCompact(byteArrayOf(9), 0x010203, 5, 1)!!.single()
        assertNull(MeshFrameCodec.decodeCompact(good.copyOf(MeshFrameCodec.COMPACT_HEADER)))
        assertNull(MeshFrameCodec.decodeCompact(good.copyOf().also { it[8] = 0 }))
        assertNull(MeshFrameCodec.decodeCompact(good.copyOf().also { it[7] = 1 }))
        assertNull(MeshFrameCodec.decodeV1(good))
    }

    @Test fun compactEpochResolution() {
        assertNull(MeshFrameCodec.resolveEpoch(0x10, oldest = 0x1_0020, current = 0x1_0070))
        assertEquals(0x1_0071L, MeshFrameCodec.resolveEpoch(0x71, oldest = 0x1_0020, current = 0x1_0070))
        assertEquals(0x1_0020L, MeshFrameCodec.resolveEpoch(0x20, oldest = 0x1_0020, current = 0x1_0070))
    }
}
