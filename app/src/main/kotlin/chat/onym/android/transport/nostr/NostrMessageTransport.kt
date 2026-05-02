package chat.onym.android.transport.nostr

import chat.onym.android.transport.InboundMessage
import chat.onym.android.transport.MessageTransport
import chat.onym.android.transport.PublishReceipt
import chat.onym.android.transport.TransportEndpoint
import chat.onym.android.transport.TransportError
import chat.onym.android.transport.TransportTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Base64

/**
 * Nostr-relay-backed [MessageTransport]. Each call to [publish]
 * builds a kind-44114 event with the topic in a `["t", topic]` tag,
 * the payload base64-encoded as `content`, and an ephemeral signer
 * for the outer pubkey so co-membership can't be inferred from the
 * relay's view. Subscribers receive every kind-44114 / kind-24114
 * event whose `t` tag matches the topic.
 *
 * Mirrors `NostrMessageTransport.swift` from onym-ios PR #12. All
 * chat-specific concerns (GroupCrypto encrypt/decrypt, SealedEnvelope,
 * BLS sender wrapper, member tracking, image / protocol message
 * routing, key resolver / epoch tag handling) are stripped — the
 * adapter does exactly: ship opaque bytes in/out.
 */
class NostrMessageTransport(
    /** Scope for relay reconnect loops + per-subscription fan-out. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MessageTransport {

    private val state = State(scope)

    override suspend fun connect(endpoints: List<TransportEndpoint>) {
        state.connect(endpoints)
    }

    override suspend fun disconnect() {
        state.disconnect()
    }

    override suspend fun publish(payload: ByteArray, topic: TransportTopic): PublishReceipt {
        val signer = OnymNostrSigner.ephemeral()
        val event = NostrEvent.build(
            kind = PRIMARY_KIND,
            tags = listOf(listOf("t", topic.rawValue)),
            content = Base64.getEncoder().encodeToString(payload),
            signer = signer,
        )
        val accepted = state.publish(event)
        return PublishReceipt(messageId = event.id, acceptedBy = accepted)
    }

    override fun subscribe(topic: TransportTopic, since: Instant?): Flow<InboundMessage> {
        // Slack on `since` for clock skew across relays; default
        // catch-up window when caller passes nothing. Same numbers
        // as iOS — preserved verbatim.
        val sinceUnix = if (since != null) {
            maxOf(0L, since.epochSecond - SINCE_SLACK_SECONDS)
        } else {
            (System.currentTimeMillis() / 1000) - DEFAULT_CATCH_UP_SECONDS
        }
        return callbackFlow {
            val job = scope.launch {
                state.subscribe(
                    topic = topic,
                    sinceUnix = sinceUnix,
                    kinds = intArrayOf(PRIMARY_KIND, LEGACY_KIND),
                    onEvent = { msg -> trySend(msg) },
                )
            }
            awaitClose {
                job.cancel()
                scope.launch { state.unsubscribe(topic) }
            }
        }
    }

    override suspend fun unsubscribe(topic: TransportTopic) {
        state.unsubscribe(topic)
    }

    /**
     * Owns the relay connections + per-topic subscription jobs.
     * Mutex-serialised mutation; reads (`connections.values`) are
     * fine because [java.util.concurrent.ConcurrentHashMap] is
     * thread-safe and the iteration order doesn't matter here.
     */
    private class State(private val scope: CoroutineScope) {
        private val mutex = Mutex()
        private val connections = mutableMapOf<URI, NostrRelayConnection>()
        private val activeSubscriptions = mutableMapOf<TransportTopic, Job>()
        // Monotonic counter for relay subscription ids. Each new
        // subscribe gets a unique id so an old stream's awaitClose
        // CLOSE can't kill a freshly opened REQ for the same topic.
        private var subscriptionGeneration: ULong = 0u

        suspend fun connect(endpoints: List<TransportEndpoint>) = mutex.withLock {
            for (endpoint in endpoints) {
                if (connections[endpoint.url] == null) {
                    val conn = NostrRelayConnection(url = endpoint.url.toString(), scope = scope)
                    connections[endpoint.url] = conn
                    conn.connect()
                }
            }
        }

        suspend fun disconnect() = mutex.withLock {
            for (job in activeSubscriptions.values) job.cancel()
            activeSubscriptions.clear()
            for (conn in connections.values) conn.disconnect()
            connections.clear()
        }

        suspend fun publish(event: NostrEvent): Int {
            val conns = mutex.withLock { connections.values.toList() }
            if (conns.isEmpty()) throw TransportError.NotConnected
            val accepted = coroutineScope {
                conns.map { conn ->
                    async {
                        try {
                            conn.publish(event)
                            true
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }.awaitAll().count { it }
            }
            if (accepted == 0) throw TransportError.PublishRejected
            return accepted
        }

        suspend fun subscribe(
            topic: TransportTopic,
            sinceUnix: Long,
            kinds: IntArray,
            onEvent: (InboundMessage) -> Unit,
        ) {
            val (subId, conns) = mutex.withLock {
                activeSubscriptions[topic]?.cancel()
                subscriptionGeneration += 1u
                val id = "msg-${topic.rawValue}-$subscriptionGeneration"
                id to connections.values.toList()
            }
            val job = scope.launch {
                for (conn in conns) {
                    val filter = JSONObject().apply {
                        put("kinds", JSONArray().also { for (k in kinds) it.put(k) })
                        put("#t", JSONArray().put(topic.rawValue))
                        put("since", sinceUnix)
                    }
                    conn.subscribe(subId, filter)
                        .onEach { event ->
                            try {
                                val payload = Base64.getDecoder().decode(event.content)
                                onEvent(
                                    InboundMessage(
                                        topic = topic,
                                        payload = payload,
                                        receivedAt = Instant.ofEpochMilli(event.displayMilliseconds),
                                        messageId = event.id,
                                    )
                                )
                            } catch (_: IllegalArgumentException) {
                                // Bad base64 — drop. Don't crash the
                                // subscription on a single bad event.
                            }
                        }
                        .launchIn(this)
                }
            }
            mutex.withLock {
                activeSubscriptions[topic] = job
            }
        }

        suspend fun unsubscribe(topic: TransportTopic) = mutex.withLock {
            activeSubscriptions[topic]?.cancel()
            activeSubscriptions.remove(topic)
        }
    }

    companion object {
        private const val PRIMARY_KIND = 44114
        private const val LEGACY_KIND = 24114
        /** Default catch-up window when the caller doesn't pass `since`. */
        private const val DEFAULT_CATCH_UP_SECONDS = 300L
        /** Tolerance applied to `since` to handle clock skew across relays. */
        private const val SINCE_SLACK_SECONDS = 60L
    }
}
