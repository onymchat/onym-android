package app.onym.android.support

import app.onym.android.transport.InboundInbox
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.PublishReceipt
import app.onym.android.transport.TransportEndpoint
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

/**
 * In-process [InboxTransport] for instrumented UI tests: routes
 * payloads between local subscribers by inbox tag, with store-and-
 * forward (a send that lands before the recipient subscribes is
 * replayed on subscribe, mirroring a Nostr relay's catch-up window).
 *
 * Lets two identities on one device exchange invitations / messages /
 * receipts with no network. Injected via `UITestRegistry.inboxTransport`.
 *
 * Mirrors `UITestLoopbackInboxTransport` from onym-ios.
 */
class LoopbackInboxTransport : InboxTransport {

    private val lock = Any()
    private val flows = mutableMapOf<String, MutableSharedFlow<InboundInbox>>()
    private var sequence = 0

    private fun flowFor(tag: String): MutableSharedFlow<InboundInbox> = synchronized(lock) {
        // Generous replay so a subscriber that attaches after several
        // sends still catches up on the whole backlog.
        flows.getOrPut(tag) {
            MutableSharedFlow(replay = 128, extraBufferCapacity = 128)
        }
    }

    override suspend fun connect(endpoints: List<TransportEndpoint>) {}

    override suspend fun disconnect() {}

    override suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt {
        val id = synchronized(lock) { "loopback-${++sequence}" }
        flowFor(inbox.rawValue).emit(
            InboundInbox(
                inbox = inbox,
                payload = payload,
                receivedAt = Instant.now(),
                messageId = id,
            ),
        )
        return PublishReceipt(messageId = id, acceptedBy = 1)
    }

    override fun subscribe(inbox: TransportInboxId): Flow<InboundInbox> =
        flowFor(inbox.rawValue).asSharedFlow()

    override suspend fun unsubscribe(inbox: TransportInboxId) {}
}
