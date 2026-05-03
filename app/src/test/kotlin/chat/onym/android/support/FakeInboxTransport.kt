package chat.onym.android.support

import chat.onym.android.transport.InboundInbox
import chat.onym.android.transport.InboxTransport
import chat.onym.android.transport.PublishReceipt
import chat.onym.android.transport.TransportEndpoint
import chat.onym.android.transport.TransportInboxId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable test fake for [InboxTransport]. Drives an interactor
 * test by exposing two driver methods alongside the protocol
 * surface:
 *
 *  - [emit] — push an [InboundInbox] into the subscriber's flow.
 *  - [finish] — close the subscriber's channel so the collecting
 *    coroutine completes naturally.
 *
 * Tracks observable side effects so tests can assert on them:
 *
 *  - [subscribeCallCount] — how many times anyone subscribed.
 *  - [unsubscribedInboxes] — every inbox that's been unsubscribed
 *    (either explicitly via [unsubscribe] or implicitly via the
 *    Flow's cancellation `onCompletion`). Mirrors production
 *    [chat.onym.android.transport.nostr.NostrInboxTransport] which
 *    routes `awaitClose` through `unsubscribe`.
 *  - [disconnectCallCount].
 *
 * Channel-per-inbox + `consumeAsFlow` (single subscriber) — gives
 * the iOS-style `AsyncStream` semantics (explicit termination on
 * [finish]) that `MutableSharedFlow` doesn't.
 *
 * Mirrors `FakeInboxTransport.swift` from onym-ios PR #16.
 */
class FakeInboxTransport : InboxTransport {

    private val mutex = Mutex()
    private val channels = mutableMapOf<TransportInboxId, Channel<InboundInbox>>()

    var subscribeCallCount: Int = 0
        private set
    var disconnectCallCount: Int = 0
        private set

    /** Every inbox that's been unsubscribed — explicitly or via Flow
     *  cancellation. Tests assert on this to verify cleanup. */
    val unsubscribedInboxes: List<TransportInboxId> get() = _unsubscribedInboxes.toList()
    private val _unsubscribedInboxes = mutableListOf<TransportInboxId>()

    /** Currently-active subscriptions. Inserts on `subscribe`, removes
     *  on `onCompletion` of the returned Flow (= unsubscribe OR
     *  cancellation). Multi-identity fan-out tests assert on the
     *  set-equals contract: "the active subscription set tracks the
     *  identity list across changes". */
    val subscribedInboxes: Set<TransportInboxId> get() = synchronized(_subscribedInboxes) { _subscribedInboxes.toSet() }
    private val _subscribedInboxes = mutableSetOf<TransportInboxId>()

    override suspend fun connect(endpoints: List<TransportEndpoint>) { /* no-op */ }

    override suspend fun disconnect() {
        disconnectCallCount += 1
        mutex.withLock {
            channels.values.forEach { it.close() }
            channels.clear()
        }
    }

    override suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt =
        PublishReceipt(messageId = "fake-${inbox.rawValue}", acceptedBy = 0)

    override fun subscribe(inbox: TransportInboxId): Flow<InboundInbox> {
        subscribeCallCount += 1
        synchronized(_subscribedInboxes) { _subscribedInboxes.add(inbox) }
        // Pre-create the channel so a test that calls emit() before
        // the collector starts doesn't hit a missing-key error;
        // unbounded buffer because tests typically know what they
        // emit and we don't want to wedge on suspension.
        val channel = synchronized(channels) {
            channels.getOrPut(inbox) { Channel(Channel.UNLIMITED) }
        }
        return channel.consumeAsFlow()
            .onCompletion {
                _unsubscribedInboxes.add(inbox)
                synchronized(_subscribedInboxes) { _subscribedInboxes.remove(inbox) }
            }
    }

    override suspend fun unsubscribe(inbox: TransportInboxId) {
        mutex.withLock {
            channels.remove(inbox)?.close()
            _unsubscribedInboxes.add(inbox)
            synchronized(_subscribedInboxes) { _subscribedInboxes.remove(inbox) }
        }
    }

    /** Push a message to whoever is subscribed to [inbox]. Creates
     *  the channel if no subscriber has connected yet (the message
     *  is then buffered; first subscriber receives it). */
    suspend fun emit(message: InboundInbox) {
        val channel = synchronized(channels) {
            channels.getOrPut(message.inbox) { Channel(Channel.UNLIMITED) }
        }
        channel.send(message)
    }

    /** Close the inbox's channel so the collecting Flow completes.
     *  No-op if no channel exists for [inbox]. */
    fun finish(inbox: TransportInboxId) {
        synchronized(channels) { channels[inbox]?.close() }
    }
}
