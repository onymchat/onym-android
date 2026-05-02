package chat.onym.android.transport.nostr

import chat.onym.android.transport.InboundInbox
import chat.onym.android.transport.InboxTransport
import chat.onym.android.transport.PublishReceipt
import chat.onym.android.transport.TransportEndpoint
import chat.onym.android.transport.TransportError
import chat.onym.android.transport.TransportInboxId
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
 * Nostr-relay-backed [InboxTransport]. Each [send] builds a
 * kind-34113 parameterised-replaceable event with the recipient
 * inbox encoded as a `["d", "sep-inbox:" + inbox]` tag (so relays
 * can serve it across reconnects), plus a `["t", inbox]` tag that
 * lets clients filter by kind-24113 / legacy paths. The payload
 * goes into `content` as base64. Subscribers receive every event
 * whose `d` or `t` tag matches their inbox identifier across the
 * three filter shapes.
 *
 * Mirrors `NostrInboxTransport.swift` from onym-ios PR #12.
 * `PendingInvitation` / `SEPRekeyEnvelope` decoding is stripped —
 * the adapter does exactly: ship opaque bytes in/out.
 */
class NostrInboxTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InboxTransport {

    private val state = State(scope)

    override suspend fun connect(endpoints: List<TransportEndpoint>) {
        state.connect(endpoints)
    }

    override suspend fun disconnect() {
        state.disconnect()
    }

    override suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt {
        val signer = OnymNostrSigner.ephemeral()
        // Outbound tag set preserved verbatim from iOS / stellar-mls.
        // Each tag carries the inbox under a different key so a
        // subscriber on any of the three filter shapes catches it.
        val tags = listOf(
            listOf("d", INBOX_TAG_PREFIX + inbox.rawValue),
            listOf("t", inbox.rawValue),
            listOf("sep_inbox", inbox.rawValue),
            listOf("sep_version", "1"),
        )
        val event = NostrEvent.build(
            kind = PRIMARY_KIND,
            tags = tags,
            content = Base64.getEncoder().encodeToString(payload),
            signer = signer,
        )
        val accepted = state.send(event)
        return PublishReceipt(messageId = event.id, acceptedBy = accepted)
    }

    override fun subscribe(inbox: TransportInboxId): Flow<InboundInbox> = callbackFlow {
        val job = scope.launch {
            state.subscribe(inbox = inbox, onEvent = { trySend(it) })
        }
        awaitClose {
            job.cancel()
            scope.launch { state.unsubscribe(inbox) }
        }
    }

    override suspend fun unsubscribe(inbox: TransportInboxId) {
        state.unsubscribe(inbox)
    }

    /**
     * Triple filter shape preserved verbatim — `#d` primary with the
     * `sep-inbox:` prefix is the canonical addressing scheme;
     * `#t` secondary catches clients that drop `d`; the legacy kind
     * 24113 keeps backward-compat with older senders.
     */
    private fun subscriptionFilters(inbox: String): List<JSONObject> = listOf(
        JSONObject().apply {
            put("kinds", JSONArray().put(PRIMARY_KIND))
            put("#d", JSONArray().put(INBOX_TAG_PREFIX + inbox))
        },
        JSONObject().apply {
            put("kinds", JSONArray().put(PRIMARY_KIND))
            put("#t", JSONArray().put(inbox))
        },
        JSONObject().apply {
            put("kinds", JSONArray().put(LEGACY_KIND))
            put("#t", JSONArray().put(inbox))
        },
    )

    private inner class State(private val scope: CoroutineScope) {
        private val mutex = Mutex()
        private val connections = mutableMapOf<URI, NostrRelayConnection>()
        private val activeSubscriptions = mutableMapOf<TransportInboxId, Job>()

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

        suspend fun send(event: NostrEvent): Int {
            val conns = mutex.withLock { connections.values.toList() }
            if (conns.isEmpty()) throw TransportError.NotConnected
            val accepted = coroutineScope {
                conns.map { conn ->
                    async {
                        try {
                            conn.publishAndAwaitOK(event)
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
            inbox: TransportInboxId,
            onEvent: (InboundInbox) -> Unit,
        ) {
            val (subBase, conns) = mutex.withLock {
                activeSubscriptions[inbox]?.cancel()
                "inbox-${inbox.rawValue}" to connections.values.toList()
            }
            val filters = subscriptionFilters(inbox.rawValue)
            val job = scope.launch {
                for (conn in conns) {
                    filters.forEachIndexed { index, filter ->
                        val filterSubId = "$subBase-$index"
                        conn.subscribe(filterSubId, filter)
                            .onEach { event ->
                                try {
                                    val payload = Base64.getDecoder().decode(event.content)
                                    onEvent(
                                        InboundInbox(
                                            inbox = inbox,
                                            payload = payload,
                                            receivedAt = Instant.ofEpochMilli(event.displayMilliseconds),
                                            messageId = event.id,
                                        )
                                    )
                                } catch (_: IllegalArgumentException) {
                                    // Bad base64 — drop.
                                }
                            }
                            .launchIn(this)
                    }
                }
            }
            mutex.withLock {
                activeSubscriptions[inbox] = job
            }
        }

        suspend fun unsubscribe(inbox: TransportInboxId) = mutex.withLock {
            activeSubscriptions[inbox]?.cancel()
            activeSubscriptions.remove(inbox)
        }
    }

    companion object {
        private const val PRIMARY_KIND = 34113
        private const val LEGACY_KIND = 24113
        private const val INBOX_TAG_PREFIX = "sep-inbox:"
    }
}
