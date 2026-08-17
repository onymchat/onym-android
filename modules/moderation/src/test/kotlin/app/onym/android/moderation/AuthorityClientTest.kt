package app.onym.android.moderation

import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.fixtureMandateRecord
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The registration transport against an in-process canned-response
 * OkHttp client: exact wire bytes out, receipt/envelope handling in,
 * and the https wall — the artifact posted here carries the user's
 * signature, so where and how it travels is part of the trust story.
 */
class AuthorityClientTest {
    private val mandate = fixtureMandateRecord().mandate

    private var requestUrl: String? = null
    private var requestBody: String? = null
    private var requestHeaders: Map<String, String> = emptyMap()

    private fun client(status: Int, body: String): OkHttpAuthorityClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestUrl = chain.request().url.toString()
                    requestHeaders = chain.request().headers.toMultimap()
                        .mapValues { (_, values) -> values.joinToString(",") }
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
        return OkHttpAuthorityClient(http)
    }

    @Test
    fun `posts the exact wire bytes and returns the receipt`() = runTest {
        val receipt = client(
            200,
            """{"mandateRef":"${mandate.mandateHash()}","accepted":true}""",
        ).registerMandate(ModerationFixtures.listing(), mandate)

        assertEquals("https://authority.example/v1/mandates", requestUrl)
        assertEquals(mandate.wireBytes().decodeToString(), requestBody)
        assertEquals(mandate.mandateHash(), receipt.mandateRef)
        assertTrue(receipt.accepted)
    }

    @Test
    fun `a non-2xx carries the authority's error envelope`() = runTest {
        try {
            client(400, """{"error":"bad_request","message":"mandate pins a different manifest"}""")
                .registerMandate(ModerationFixtures.listing(), mandate)
            fail("a 400 must throw")
        } catch (e: AuthorityRejectedException) {
            assertEquals(400, e.statusCode)
            assertEquals("bad_request", e.rawCode)
            assertTrue(e.isDeterministic)
        }
    }

    @Test
    fun `deterministic excludes the transient pair`() {
        assertTrue(AuthorityRejectedException(404, null, "x").isDeterministic)
        assertTrue(!AuthorityRejectedException(408, null, "x").isDeterministic)
        assertTrue(!AuthorityRejectedException(429, null, "x").isDeterministic)
        assertTrue(!AuthorityRejectedException(500, null, "x").isDeterministic)
        assertTrue(!AuthorityRejectedException(503, null, "x").isDeterministic)
    }

    /** An unparseable 2xx may follow a server-side commit — retryable,
     * never a refusal (exact replay is idempotent server-side). */
    @Test
    fun `an unparseable 2xx is unreachable not rejected`() = runTest {
        try {
            client(200, "<html>proxy interlude</html>")
                .registerMandate(ModerationFixtures.listing(), mandate)
            fail("garbage must throw")
        } catch (e: AuthorityUnreachableException) {
            assertTrue(e.message!!.contains("unparseably"))
        }
    }

    /** Schemes are case-insensitive (RFC 3986 §3.1): an `HTTPS://`
     * directory entry is secure and must post, not degrade into a
     * permanently-retried "unreachable". */
    @Test
    fun `an uppercase https scheme is accepted`() = runTest {
        val receipt = client(
            200,
            """{"mandateRef":"${mandate.mandateHash()}","accepted":true}""",
        ).registerMandate(
            ModerationFixtures.listing().copy(apiBaseURL = "HTTPS://authority.example"),
            mandate,
        )
        assertTrue(receipt.accepted)
    }

    /** The signed consent artifact never travels plaintext: a non-https
     * directory entry degrades like an outage (retried later), it does
     * not post. */
    @Test
    fun `a plain-http listing is refused before any request`() = runTest {
        try {
            client(200, """{"mandateRef":"x","accepted":true}""").registerMandate(
                ModerationFixtures.listing().copy(apiBaseURL = "http://authority.example"),
                mandate,
            )
            fail("plain http must refuse")
        } catch (e: AuthorityUnreachableException) {
            assertTrue(e.message!!.contains("https"))
        }
        assertEquals(null, requestUrl)
    }

    // ─── report filing transport ─────────────────────────────────

    @Test
    fun `fileReport posts the exact bytes and decodes the receipt`() = runTest {
        val wireBytes = """{"reportId":"report-1","signature":"c2ln"}""".encodeToByteArray()
        val receipt = client(
            200,
            """{"reportId":"report-1","receivedAt":"2026-08-16T00:00:01Z","caseId":"case-9"}""",
        ).fileReport(ModerationFixtures.listing(), wireBytes)

        assertEquals("https://authority.example/v1/reports", requestUrl)
        assertEquals(wireBytes.decodeToString(), requestBody)
        assertEquals("case-9", receipt.caseId)
        // The report's own signature is the auth — no signed headers.
        assertTrue(requestHeaders.keys.none { it.startsWith("x-onym", ignoreCase = true) })
    }

    @Test
    fun `fileReport surfaces the authority's refusal envelope`() = runTest {
        try {
            client(422, """{"error":"authenticity_unverified","message":"item 0 fails"}""")
                .fileReport(ModerationFixtures.listing(), ByteArray(2))
            fail("a 422 must throw")
        } catch (e: AuthorityRejectedException) {
            assertEquals(422, e.statusCode)
            assertEquals("authenticity_unverified", e.rawCode)
            assertTrue(e.isDeterministic)
        }
    }

    @Test
    fun `uploadEvidenceImage puts the raw bytes under the signed headers`() = runTest {
        val sha = "1a".repeat(32)
        client(200, """{"sha256":"$sha"}""").uploadEvidenceImage(
            listing = ModerationFixtures.listing(),
            sha256 = sha,
            bytes = byteArrayOf(1, 2, 3),
            userKey = "onym:key:aabb",
            timestamp = "2026-08-16T00:00:00Z",
            signatureBase64 = "c2ln",
        )

        assertEquals("https://authority.example/v1/evidence-blobs/$sha", requestUrl)
        assertEquals("onym:key:aabb", requestHeaders["x-onym-key"])
        assertEquals("2026-08-16T00:00:00Z", requestHeaders["x-onym-timestamp"])
        assertEquals("c2ln", requestHeaders["x-onym-signature"])
    }
}
