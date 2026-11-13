package app.onym.android.mesh

import app.onym.android.transport.InboundDelivery
import app.onym.android.transport.InboundInbox
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.PublishReceipt
import app.onym.android.transport.TransportEndpoint
import app.onym.android.transport.TransportError
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** One (local inbox, peer) pair the mesh can deliver for. */
data class MeshContact(val inbox: TransportInboxId, val pairKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is MeshContact && inbox == other.inbox && pairKey.contentEquals(other.pairKey)

    override fun hashCode(): Int = 31 * inbox.hashCode() + pairKey.contentHashCode()
}

/**
 * The Bluetooth mesh as an [InboxTransport] — receive only, as on iOS.
 *
 * It never transmits: [send] always throws, so it can't be put on a send
 * path by mistake, and nothing it does tells a broadcaster a frame was
 * heard. Delivered envelopes are marked [InboundDelivery.BLUETOOTH] so the
 * dispatcher sends no receipt for them.
 *
 * Matching is local and silent: for each frame's epoch it computes every
 * contact's tag and keeps the frame only on a match.
 */
class MeshInboxTransport(
    private val frames: Flow<ByteArray>,
    private val contacts: suspend () -> List<MeshContact>,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val contactsRefresh: Duration = Duration.ofSeconds(60),
) : InboxTransport {

    private val delivered = MutableSharedFlow<InboundInbox>(extraBufferCapacity = 64)
    private val lock = Mutex()
    private val reassembler = MeshReassembler()
    private var listening: Job? = null
    private var knownContacts: List<MeshContact> = emptyList()
    private var contactsLoadedAt: Instant? = null
    private val tagCache = HashMap<Long, Map<Int, Set<TransportInboxId>>>()
    private val decoders = HashMap<Long, Pair<MeshFountain.Decoder, Instant>>()
    private val codedDelivered = HashMap<Long, Instant>()

    /** Starts listening. Endpoints mean nothing to a radio. */
    override suspend fun connect(endpoints: List<TransportEndpoint>) {
        lock.withLock {
            if (listening == null) listening = scope.launch { frames.collect { receive(it) } }
        }
    }

    override suspend fun disconnect() {
        lock.withLock {
            listening?.cancel()
            listening = null
        }
    }

    override suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt =
        throw TransportError.NotConnected

    override fun subscribe(inbox: TransportInboxId): Flow<InboundInbox> = delivered.filter { it.inbox == inbox }

    override suspend fun unsubscribe(inbox: TransportInboxId) = Unit

    /** One frame's bytes: decode, check the epoch, match the tag, reassemble, deliver. */
    internal suspend fun receive(bytes: ByteArray) {
        val date = now()
        val current = MeshEpoch.number(date)
        val oldest = MeshEpoch.number(date.minus(RETENTION))
        if (bytes.firstOrNull() == MeshCodedFrame.KIND) {
            val coded = MeshCodedFrame.decode(bytes) ?: return
            val epoch = MeshFrameCodec.resolveEpoch(coded.epochLow, oldest, current) ?: return
            receiveCoded(coded, epoch, date)
            return
        }
        val frame = when (bytes.firstOrNull()) {
            MeshFrameCodec.V1_VERSION -> MeshFrameCodec.decodeV1(bytes)
            MeshFrameCodec.COMPACT_KIND -> MeshFrameCodec.decodeCompact(bytes)?.let { c ->
                MeshFrameCodec.resolveEpoch(c.epochLow, oldest, current)?.let(c::toFrame)
            }
            else -> null
        } ?: return
        if (frame.epoch < oldest || frame.epoch > current + 1) return

        val inboxes: Set<TransportInboxId>
        val envelope: ByteArray
        lock.withLock {
            refreshContactsIfStale(date)
            inboxes = tags(frame.epoch)[frame.tag] ?: return
            envelope = reassembler.add(frame, date) ?: return
        }
        val messageId = "ble:%016x".format(frame.envelopeId)
        for (inbox in inboxes) {
            delivered.emit(InboundInbox(inbox, envelope, date, messageId, InboundDelivery.BLUETOOTH))
        }
    }

    /** Coded frames: any `k` independent ones rebuild the binary envelope,
     *  delivered as the inbox's JSON. */
    private suspend fun receiveCoded(frame: MeshCodedFrame, epoch: Long, date: Instant) {
        val key = frame.key(epoch)
        val inboxes: Set<TransportInboxId>
        val json: ByteArray
        lock.withLock {
            refreshContactsIfStale(date)
            inboxes = tags(epoch)[frame.tag] ?: return
            decoders.entries.removeIf { Duration.between(it.value.second, date) >= DECODER_TIMEOUT }
            codedDelivered.entries.removeIf { Duration.between(it.value, date) >= DELIVERED_MEMORY }
            if (codedDelivered.containsKey(key)) return
            val entry = decoders[key] ?: (MeshFountain.Decoder(frame.k, frame.envelopeId) to date)
            // The first frame sets the envelope's size; one that disagrees is noise.
            if (entry.first.k != frame.k) return
            val binary = entry.first.add(frame.index, frame.symbol)
            if (binary == null) {
                if (!decoders.containsKey(key) && decoders.size >= MAX_DECODERS) {
                    decoders.minByOrNull { it.value.second }?.let { decoders.remove(it.key) }
                }
                decoders[key] = entry
                return
            }
            decoders.remove(key)
            codedDelivered[key] = date
            json = MeshEnvelope.jsonFromBinary(binary) ?: return
        }
        val messageId = "ble:%016x".format(key)
        for (inbox in inboxes) {
            delivered.emit(InboundInbox(inbox, json, date, messageId, InboundDelivery.BLUETOOTH))
        }
    }

    private suspend fun refreshContactsIfStale(date: Instant) {
        val loaded = contactsLoadedAt
        if (loaded != null && Duration.between(loaded, date) < contactsRefresh) return
        val fresh = contacts()
        contactsLoadedAt = date
        if (fresh != knownContacts) {
            knownContacts = fresh
            tagCache.clear()
        }
    }

    private fun tags(epoch: Long): Map<Int, Set<TransportInboxId>> = tagCache.getOrPut(epoch) {
        if (tagCache.size > 100) tagCache.keys.minOrNull()?.let(tagCache::remove)
        val table = HashMap<Int, MutableSet<TransportInboxId>>()
        for (c in knownContacts) table.getOrPut(MeshTag.derive(c.pairKey, epoch)) { HashSet() }.add(c.inbox)
        table
    }

    companion object {
        /** How far back a broadcaster may still be looping an envelope. */
        val RETENTION: Duration = Duration.ofHours(24)

        /** Partial progress is kept this long — many laps' worth. */
        private val DECODER_TIMEOUT: Duration = Duration.ofMinutes(30)
        private val DELIVERED_MEMORY: Duration = Duration.ofMinutes(30)
        private const val MAX_DECODERS = 64
    }
}
