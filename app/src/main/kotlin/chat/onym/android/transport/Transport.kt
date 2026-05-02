package chat.onym.android.transport

import kotlinx.coroutines.flow.Flow
import java.net.URI
import java.time.Instant

/**
 * Transport-agnostic message-passing surface. Mirrors `Transport.swift`
 * from onym-ios PR #12 1:1 — same value types, same operation shapes,
 * same name conventions translated to Kotlin / coroutines / Flow.
 *
 * The abstract layer is what the chat repository (landing in a
 * subsequent chunk) talks to. Concrete adapters under `transport/nostr/`
 * (today) and a future `transport/tor/` implement these interfaces;
 * no `NostrEvent`, kind, tag, or relay concept may leak across the
 * boundary.
 */

/**
 * A reachable transport endpoint. The URI scheme is interpreted by
 * the concrete transport: `wss://` for Nostr relays, `onion://` for
 * a future Tor hidden-service transport, etc.
 */
data class TransportEndpoint(val url: URI)

/**
 * Opaque broadcast topic identifier. The transport is free to map
 * this onto its own routing primitive (a Nostr `["t", topic]` tag,
 * a Tor pubsub channel name, …); callers must treat it as a stable
 * string that identifies a many-to-many channel.
 */
@JvmInline
value class TransportTopic(val rawValue: String)

/**
 * Opaque inbox identifier — a recipient-derived handle that lets a
 * sender reach exactly one receiver without learning their long-term
 * identity. Derivation is the application's job (e.g.
 * [chat.onym.android.identity.Identity.inboxTag]); the transport
 * only routes by it.
 */
@JvmInline
value class TransportInboxId(val rawValue: String)

/**
 * One inbound payload as observed by a topic subscriber. The
 * transport has already validated whatever framing it was
 * responsible for (Nostr event-ID integrity, signature, …); [payload]
 * is the opaque bytes the sender called [MessageTransport.publish]
 * with.
 */
data class InboundMessage(
    val topic: TransportTopic,
    val payload: ByteArray,
    /** Wall-clock timestamp the transport reports for this message. */
    val receivedAt: Instant,
    /**
     * Transport-assigned unique identifier (e.g. NIP-01 event id) that
     * callers can use to dedupe across redundant endpoints.
     */
    val messageId: String,
) {
    // Default data-class equals/hashCode use reference equality for
    // ByteArray. Override with content-based comparison so two
    // InboundMessage instances with the same bytes compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboundMessage) return false
        return topic == other.topic &&
                payload.contentEquals(other.payload) &&
                receivedAt == other.receivedAt &&
                messageId == other.messageId
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + messageId.hashCode()
        return result
    }
}

/** Inbox variant of [InboundMessage]. */
data class InboundInbox(
    val inbox: TransportInboxId,
    val payload: ByteArray,
    val receivedAt: Instant,
    val messageId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboundInbox) return false
        return inbox == other.inbox &&
                payload.contentEquals(other.payload) &&
                receivedAt == other.receivedAt &&
                messageId == other.messageId
    }

    override fun hashCode(): Int {
        var result = inbox.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + messageId.hashCode()
        return result
    }
}

/**
 * Acknowledgement returned by [MessageTransport.publish] /
 * [InboxTransport.send]. [acceptedBy] is the number of endpoints
 * that confirmed acceptance — for Nostr that's the count of relays
 * that returned `OK true`. Concrete transports may treat
 * "no response within timeout" as acceptance to avoid blocking.
 */
data class PublishReceipt(
    val messageId: String,
    val acceptedBy: Int,
)

/**
 * Transport-layer failure. Sealed so `when` exhaustiveness checks
 * downstream don't need an `else` branch.
 */
sealed class TransportError(message: String) : Exception(message) {
    object NotConnected : TransportError("transport is not connected to any endpoint") {
        private fun readResolve(): Any = NotConnected
    }
    object PublishRejected : TransportError("no endpoint accepted the publish") {
        private fun readResolve(): Any = PublishRejected
    }
    class InvalidPayload(reason: String) : TransportError("invalid payload: $reason")
}

/**
 * Many-to-many topic-addressed transport. A [MessageTransport]
 * carries opaque [ByteArray] payloads between any number of
 * publishers and subscribers that share a topic. Senders are not
 * authenticated by the transport — that's the application layer's
 * responsibility.
 *
 * The iOS twin uses `AsyncStream`; the Kotlin equivalent is
 * [Flow]. The returned Flow is cold — the transport opens its
 * subscription on first collection and tears it down on cancellation.
 */
interface MessageTransport {
    suspend fun connect(endpoints: List<TransportEndpoint>)
    suspend fun disconnect()

    suspend fun publish(payload: ByteArray, topic: TransportTopic): PublishReceipt

    /**
     * Subscribe to a topic. [since] lets the caller request a
     * catch-up window; if `null`, the transport picks a sensible
     * recent default. The Flow cancels its underlying subscription
     * when the consumer stops collecting or the scope is cancelled.
     */
    fun subscribe(topic: TransportTopic, since: Instant? = null): Flow<InboundMessage>

    suspend fun unsubscribe(topic: TransportTopic)
}

/**
 * Recipient-addressed transport. Unlike [MessageTransport], each
 * payload targets exactly one inbox. A receiver subscribes by their
 * own inbox identifier; senders address them by the same
 * identifier. The transport makes no claim about who the sender is.
 */
interface InboxTransport {
    suspend fun connect(endpoints: List<TransportEndpoint>)
    suspend fun disconnect()

    suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt

    fun subscribe(inbox: TransportInboxId): Flow<InboundInbox>

    suspend fun unsubscribe(inbox: TransportInboxId)
}
