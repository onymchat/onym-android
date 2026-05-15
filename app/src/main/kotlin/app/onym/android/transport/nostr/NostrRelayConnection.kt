package app.onym.android.transport.nostr

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Persistent WebSocket connection to a single Nostr relay. Owns the
 * REQ/EVENT/EOSE/OK/CLOSE framing and is the only place in the
 * transport layer that touches OkHttp's [WebSocket] API. Reconnect
 * with exponential backoff + heartbeat (via OkHttp's `pingInterval`)
 * + per-publish OK await are all internal — the surface for callers
 * is [connect], [disconnect], [publish], [publishAndAwaitOK],
 * [subscribe], [unsubscribe].
 *
 * Ported from `stellar-mls/clients/android/StellarChat/.../nostr/
 * NostrRelayConnection.kt` (the Android impl) with three changes:
 *
 *   - References to `SecurityLog` and `BuildConfig` (live in the
 *     stellar-mls app module) replaced with [Log.w] tags.
 *   - Reconnect's `Thread.sleep` replaced with coroutine [delay] on
 *     an injected [scope] — the OkHttp listener thread is a worker
 *     dispatcher, blocking it for up to 2 minutes is rude.
 *   - `disconnect()` no longer wipes the subscriptions map. Matches
 *     the iOS twin (`NostrRelayConnection.swift` in onym-ios PR #12)
 *     so subscriptions survive an explicit disconnect+reconnect
 *     cycle and only the per-subscription unsubscribe clears state.
 *
 * Heartbeat: OkHttp's built-in `pingInterval(30s)` sends WebSocket
 * ping frames; relays that respect NIP-01 reply with pong. iOS
 * needs the `["CLOSE","__hb"]` workaround because `URLSession`'s
 * pong handler has a known CFNetwork crash; Android doesn't.
 */
class NostrRelayConnection(
    private val url: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 0 read-timeout — WebSocket is long-lived; never let OkHttp
        // tear it down because no message arrived in N seconds.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build(),
) {
    private var webSocket: WebSocket? = null
    private val subscriptions = ConcurrentHashMap<String, Pair<JSONObject, (NostrEvent) -> Unit>>()

    @Volatile private var reconnectAttempts = 0
    @Volatile var isConnected: Boolean = false
        private set

    /** Callback for relay OK responses: `(eventId, accepted)`. */
    var onOK: ((String, Boolean) -> Unit)? = null

    /** Notifies callers when the connection state flips, so a higher
     *  layer can react (e.g. show a "reconnecting…" indicator). */
    var onConnectionStateChange: ((Boolean) -> Unit)? = null

    private val pendingOKs = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun connect() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempts = 0
                    isConnected = true
                    onConnectionStateChange?.invoke(true)
                    // Replay subscriptions through the new connection.
                    // ConcurrentHashMap iteration is safe under
                    // concurrent mutation; we may miss a subscription
                    // added between the loop start and end, but the
                    // caller's `subscribe` always re-sends the REQ
                    // immediately so nothing is lost.
                    for ((subId, pair) in subscriptions) {
                        sendReq(subId, pair.first)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val wasConnected = isConnected
                    isConnected = false
                    if (wasConnected) onConnectionStateChange?.invoke(false)

                    // Exponential backoff. Reconnect runs on the
                    // injected scope so we don't block OkHttp's
                    // listener dispatcher.
                    reconnectAttempts++
                    val delayMs = minOf(
                        MAX_RECONNECT_DELAY_MS,
                        BASE_RECONNECT_DELAY_MS * (1L shl minOf(reconnectAttempts - 1, 6)),
                    )
                    scope.launch {
                        delay(delayMs)
                        connect()
                    }
                }
            },
        )
    }

    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE_CODE, "Bye")
        webSocket = null
        isConnected = false
        reconnectAttempts = 0
        // Subscriptions intentionally preserved — see class doc. An
        // explicit disconnect does not wipe the per-subscription
        // continuations; only `unsubscribe(id)` does.
    }

    /** Best-effort send. Returns immediately; the relay's ACK (if
     *  any) arrives later via [onOK]. Callers that need the ACK
     *  should use [publishAndAwaitOK]. */
    fun publish(event: NostrEvent) {
        val frame = JSONArray().apply {
            put("EVENT")
            put(event.toJson())
        }
        webSocket?.send(frame.toString())
    }

    /**
     * Publish and wait for the relay's OK for this event id. Returns
     * `true` on `OK true`. Treats a [PUBLISH_TIMEOUT_MS] silence as
     * acceptance to avoid hanging on relays that drop OK frames —
     * matches iOS's twin behaviour.
     *
     * The pending-OK entry is registered BEFORE the send so a fast
     * OK can never be missed.
     */
    suspend fun publishAndAwaitOK(event: NostrEvent): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingOKs[event.id] = deferred

        val frame = JSONArray().apply {
            put("EVENT")
            put(event.toJson())
        }
        val sent = webSocket?.send(frame.toString()) ?: false
        if (!sent) {
            pendingOKs.remove(event.id)
            return false
        }
        return withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
            deferred.await()
        } ?: run {
            pendingOKs.remove(event.id)
            // Treat timeout as success — event was sent, relay just
            // didn't ACK. Better than blocking the chat layer
            // indefinitely on a flaky relay.
            true
        }
    }

    /** Subscribe via callbackFlow. The flow's awaitClose CLOSEs the
     *  subscription on the relay when the consumer cancels. */
    fun subscribe(subscriptionId: String, filter: JSONObject): Flow<NostrEvent> = callbackFlow {
        subscriptions[subscriptionId] = filter to { event -> trySend(event) }
        sendReq(subscriptionId, filter)

        awaitClose {
            subscriptions.remove(subscriptionId)
            val close = JSONArray().apply {
                put("CLOSE")
                put(subscriptionId)
            }
            webSocket?.send(close.toString())
        }
    }

    // ─── Private ────────────────────────────────────────────────────

    private fun sendReq(subscriptionId: String, filter: JSONObject) {
        val frame = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filter)
        }
        webSocket?.send(frame.toString())
    }

    private fun handleMessage(text: String) {
        // Reject oversized inbound frames — a malicious relay should
        // not be able to OOM the app by streaming megabytes per
        // message. 1 MB is the same cap the stellar-mls / iOS twin
        // applies.
        if (text.length > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "$url: oversized message rejected (${text.length} bytes)")
            return
        }
        try {
            val array = JSONArray(text)
            when (array.getString(0)) {
                "EVENT" -> {
                    if (array.length() < 3) return
                    val subId = array.getString(1)
                    val event = NostrEvent.fromJson(array.getJSONObject(2)) ?: return
                    if (!event.verifyEventId()) {
                        Log.w(TAG, "$url: invalid event id rejected (sub=$subId)")
                        return
                    }
                    subscriptions[subId]?.second?.invoke(event)
                }
                "EOSE" -> {
                    // End of stored events for the named subscription.
                    // No-op: callers don't currently need the
                    // historical-vs-live boundary.
                }
                "OK" -> {
                    if (array.length() < 3) return
                    val eventId = array.getString(1)
                    val accepted = array.getBoolean(2)
                    pendingOKs.remove(eventId)?.complete(accepted)
                    onOK?.invoke(eventId, accepted)
                }
                "NOTICE" -> {
                    // Relay informational text; callers don't currently
                    // need to surface this anywhere.
                }
                else -> { /* unknown frame type, ignore */ }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$url: malformed frame rejected: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NostrRelayConnection"
        private const val MAX_RECONNECT_DELAY_MS = 120_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_MESSAGE_SIZE = 1_048_576
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val PING_INTERVAL_SECONDS = 30L
        private const val PUBLISH_TIMEOUT_MS = 5_000L
        private const val NORMAL_CLOSURE_CODE = 1000
    }
}
