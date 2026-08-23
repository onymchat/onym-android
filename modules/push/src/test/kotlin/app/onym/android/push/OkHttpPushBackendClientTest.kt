package app.onym.android.push

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The push transport against an in-process canned-response OkHttp
 * client (the AuthorityClientTest pattern — a hand-rolled
 * Interceptor, no MockWebServer): exact wire bytes out, tolerant
 * decode in, the https wall, and the {error,message} refusal
 * vocabulary.
 */
class OkHttpPushBackendClientTest {
    private var requestUrl: String? = null
    private var requestMethod: String? = null
    private var requestBody: String? = null

    private fun client(
        status: Int,
        body: String,
        baseUrl: String = "https://push.example",
        allowInsecureLoopback: Boolean = false,
    ): OkHttpPushBackendClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestUrl = chain.request().url.toString()
                    requestMethod = chain.request().method
                    requestBody = Buffer().also {
                        chain.request().body?.writeTo(it)
                    }.readUtf8()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("canned")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return OkHttpPushBackendClient(http, baseUrl, allowInsecureLoopback)
    }

    private fun registerRequest(integrityToken: String?): PushRegisterRequest {
        val serverPublic = X25519PrivateKeyParameters(ByteArray(32) { 7 }, 0)
            .generatePublicKey().encoded
        return PushRegisterRequest(
            userKey = "onym:key:aabb",
            timestamp = "2026-08-22T12:00:00Z",
            signature = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            challenge = ByteArray(32) { 0x42 },
            integrityToken = integrityToken,
            tokenEnvelope = PushTokenEnvelope.seal("token-fixture", serverPublic),
            subscriptions = listOf(
                PushSubscription("a1b2c3d4e5f60718", listOf("wss://nostr.onym.app")),
            ),
        )
    }

    @Test
    fun `register posts the camelCase wire shape`() = runTest {
        client(200, """{"expiresAt":"2026-09-21T12:00:00Z"}""")
            .register(registerRequest(integrityToken = "integrity-fixture"))

        assertEquals("https://push.example/v1/register", requestUrl)
        assertEquals("POST", requestMethod)
        val body = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("onym:key:aabb", body["userKey"]!!.jsonPrimitive.content)
        assertEquals("2026-08-22T12:00:00Z", body["timestamp"]!!.jsonPrimitive.content)
        assertEquals("integrity-fixture", body["integrityToken"]!!.jsonPrimitive.content)
        // The challenge travels back as base64 of the exact bytes.
        assertEquals(
            Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
            body["challenge"]!!.jsonPrimitive.content,
        )
        val envelope = body["tokenEnvelope"]!!.jsonObject
        assertEquals(
            32,
            Base64.getDecoder().decode(
                envelope["ephemeralPublicKey"]!!.jsonPrimitive.content,
            ).size,
        )
        assertTrue(requestBody!!.contains("\"subscriptions\""))
        assertTrue(requestBody!!.contains("\"a1b2c3d4e5f60718\""))
    }

    /** Absent, not null: the backend's serde treats the two
     * differently, and a fabricated `integrityToken: null` member
     * would be a lie about what Google answered. */
    @Test
    fun `a missing integrity token is omitted from the body`() = runTest {
        client(200, """{"expiresAt":"2026-09-21T12:00:00Z"}""")
            .register(registerRequest(integrityToken = null))
        assertFalse(requestBody!!.contains("integrityToken"))
    }

    @Test
    fun `register returns the expiry`() = runTest {
        val registration = client(200, """{"expiresAt":"2026-09-21T12:00:00Z"}""")
            .register(registerRequest(integrityToken = null))
        assertEquals("2026-09-21T12:00:00Z", registration.expiresAt)
    }

    @Test
    fun `challenge posts the purpose and decodes the bytes`() = runTest {
        val challengeB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })
        val issued = client(
            200,
            """{"challenge":"$challengeB64","expiresAt":"2026-08-22T12:10:00Z"}""",
        ).fetchChallenge("register")

        assertEquals("https://push.example/v1/challenge", requestUrl)
        assertEquals("""{"purpose":"register"}""", requestBody)
        assertTrue(issued.challenge.contentEquals(ByteArray(32) { 0x42 }))
    }

    @Test
    fun `the registration key decodes to raw bytes`() = runTest {
        val keyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val key = client(200, """{"publicKey":"$keyB64"}""").fetchRegistrationKey()
        assertEquals("https://push.example/v1/registration-key", requestUrl)
        assertEquals("GET", requestMethod)
        assertTrue(key.publicKey.contentEquals(ByteArray(32) { 9 }))
    }

    @Test
    fun `unregister accepts the idempotent ok`() = runTest {
        // Just "does not throw" — the 200 body carries nothing the
        // caller needs.
        client(200, """{"status":"ok"}""").unregister(
            PushUnregisterRequest(
                userKey = "onym:key:aabb",
                timestamp = "2026-08-22T12:00:00Z",
                signature = "c2ln",
                challenge = ByteArray(32) { 0x42 },
                tokenEnvelope = registerRequest(null).tokenEnvelope,
            ),
        )
        assertEquals("https://push.example/v1/unregister", requestUrl)
        assertFalse(requestBody!!.contains("subscriptions"))
    }

    @Test
    fun `a refusal carries the typed error vocabulary`() = runTest {
        try {
            client(400, """{"error":"relay_invalid","message":"not a wss relay"}""")
                .register(registerRequest(null))
            fail("a 400 must throw")
        } catch (e: PushBackendRejectedException) {
            assertEquals(400, e.statusCode)
            assertEquals("relay_invalid", e.rawCode)
            assertEquals(PushBackendErrorCode.RELAY_INVALID, e.code)
            assertEquals("not a wss relay", e.message)
        }
    }

    @Test
    fun `every published code maps and unknown codes stay refusals`() {
        assertEquals(
            PushBackendErrorCode.SIGNATURE_INVALID,
            PushBackendErrorCode.fromRaw("signature_invalid"),
        )
        assertEquals(PushBackendErrorCode.CAPACITY, PushBackendErrorCode.fromRaw("capacity"))
        assertEquals(PushBackendErrorCode.BAD_REQUEST, PushBackendErrorCode.fromRaw("bad_request"))
        assertEquals(PushBackendErrorCode.INTERNAL, PushBackendErrorCode.fromRaw("internal_error"))
        assertEquals(PushBackendErrorCode.UNKNOWN, PushBackendErrorCode.fromRaw("grew_a_code"))
        assertEquals(PushBackendErrorCode.UNKNOWN, PushBackendErrorCode.fromRaw(null))
    }

    /** A non-2xx whose body isn't the {error,message} envelope (an
     * HTML error page from a proxy, an empty body) still classifies
     * as a refusal — with the synthetic message and a null rawCode,
     * which the typed classification maps to UNKNOWN. */
    @Test
    fun `an unparseable refusal body falls back to the synthetic message`() = runTest {
        try {
            client(502, "<html>bad gateway</html>").register(registerRequest(null))
            fail("a 502 must throw")
        } catch (e: PushBackendRejectedException) {
            assertEquals(502, e.statusCode)
            assertNull(e.rawCode)
            assertEquals(PushBackendErrorCode.UNKNOWN, e.code)
            assertEquals("push backend answered HTTP 502", e.message)
        }
    }

    /** Pins the CURRENT 5xx classification: a 5xx lands as Rejected
     * (not Unreachable). Behaviorally fine under the interactor —
     * the deterministic flag is false for every 5xx, so the
     * reconciler treats it as retryable either way; this test is a
     * note, not a defect (PR #257 review). */
    @Test
    fun `a 5xx is a rejection but never a deterministic one`() = runTest {
        try {
            client(500, """{"error":"internal_error","message":"scripted"}""")
                .register(registerRequest(null))
            fail("a 500 must throw")
        } catch (e: PushBackendRejectedException) {
            assertEquals(500, e.statusCode)
            assertEquals(PushBackendErrorCode.INTERNAL, e.code)
            assertFalse(e.deterministic)
        }
    }

    /** The rejected-vs-retryable boundary the reconciler consumes:
     * plain 4xx refusals are deterministic; 429 and the capacity
     * code (either alone) and every 5xx are not. */
    @Test
    fun `the deterministic flag draws the retry boundary`() {
        fun rejected(status: Int, raw: String?) =
            PushBackendRejectedException(status, raw, "scripted")
        assertTrue(rejected(400, "bad_request").deterministic)
        assertTrue(rejected(400, "signature_invalid").deterministic)
        assertTrue(rejected(400, "relay_invalid").deterministic)
        assertTrue(rejected(404, null).deterministic)
        assertFalse(rejected(429, "capacity").deterministic)
        assertFalse(rejected(429, null).deterministic)
        assertFalse(rejected(400, "capacity").deterministic)
        assertFalse(rejected(500, "internal_error").deterministic)
        assertFalse(rejected(503, null).deterministic)
    }

    /** The linchpin of the retry classification (PR #257 review): a
     * THROWN IOException — the genuinely-offline device — must map to
     * PushBackendUnreachableException, i.e. retryable. Mutated to a
     * deterministic rejection, an offline device would permanently
     * kill its self-wake and leave the backend watching relays
     * forever. */
    @Test
    fun `a thrown IOException maps to unreachable`() = runTest {
        val http = OkHttpClient.Builder()
            .addInterceptor { throw java.io.IOException("scripted network failure") }
            .build()
        val client = OkHttpPushBackendClient(http, "https://push.example")
        try {
            client.fetchChallenge("register")
            fail("an IOException must surface typed")
        } catch (e: PushBackendUnreachableException) {
            assertTrue(e.cause is java.io.IOException)
        }
    }

    /** An unparseable 2xx is a broken deploy or a proxy interlude —
     * retry-later, never a refusal. */
    @Test
    fun `an unparseable 2xx is unreachable not rejected`() = runTest {
        try {
            client(200, "<html>proxy interlude</html>").register(registerRequest(null))
            fail("garbage must throw")
        } catch (e: PushBackendUnreachableException) {
            assertTrue(e.message!!.contains("unparseably"))
        }
    }

    /** The signed registration (inbox tags + the sealed token) never
     * travels plaintext. */
    @Test
    fun `a non-https base URL refuses to send`() = runTest {
        requestUrl = null
        try {
            client(200, "{}", baseUrl = "http://push.example").fetchChallenge("register")
            fail("http must not send")
        } catch (_: PushBackendUnreachableException) {
            assertNull("nothing may reach the wire", requestUrl)
        }
    }

    @Test
    fun `emulator loopback is honored only when allowed`() = runTest {
        // Debug-build posture: allowed.
        client(
            200,
            """{"status":"ok"}""",
            baseUrl = "http://10.0.2.2:8080",
            allowInsecureLoopback = true,
        ).unregister(
            PushUnregisterRequest(
                userKey = "onym:key:aabb",
                timestamp = "2026-08-22T12:00:00Z",
                signature = "c2ln",
                challenge = ByteArray(32),
                tokenEnvelope = registerRequest(null).tokenEnvelope,
            ),
        )
        assertEquals("http://10.0.2.2:8080/v1/unregister", requestUrl)

        // Release posture: refused — and, like the https-wall test,
        // provably before anything reached the wire (PR #257 review).
        requestUrl = null
        try {
            client(200, "{}", baseUrl = "http://10.0.2.2:8080").fetchChallenge("register")
            fail("loopback without the allowance must not send")
        } catch (_: PushBackendUnreachableException) {
            assertNull("nothing may reach the wire", requestUrl)
        }
    }

    /** A prefix match accepted `http://localhost.attacker.example`. */
    @Test
    fun `loopback is a host comparison not a prefix`() {
        assertTrue(OkHttpPushBackendClient.isLoopbackHost("http://localhost:8080/x"))
        assertTrue(OkHttpPushBackendClient.isLoopbackHost("http://10.0.2.2"))
        assertFalse(OkHttpPushBackendClient.isLoopbackHost("http://localhost.attacker.example"))
        assertFalse(OkHttpPushBackendClient.isLoopbackHost("http://10.0.2.2.evil.test"))
        assertFalse(OkHttpPushBackendClient.isLoopbackHost("https://localhost"))
    }

    /** The client SENDS seconds precision; the backend may ANSWER
     * with fractional seconds. Both must parse. */
    @Test
    fun `instant parsing tolerates fractional seconds`() {
        // Bare parses — each either succeeds or throws; comparing two
        // parseInstant results to each other would assert a JDK
        // identity, not client behavior (PR #257 review).
        PushJson.parseInstant("2026-08-22T12:00:00Z")
        PushJson.parseInstant("2026-08-22T12:00:00.123456Z")
        try {
            PushJson.parseInstant("yesterday, around noon")
            fail("not a timestamp")
        } catch (_: IllegalArgumentException) {
        }
    }
}
