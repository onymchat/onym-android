package chat.onym.android.chain

import chat.onym.android.support.FakeOkHttpClient
import chat.onym.android.support.FakeSepContractTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin unit tests for [SepContractClient]. Two layers:
 *
 *  1. **Wire-shape pins** via [FakeSepContractTransport] — the fake
 *     captures the encoded invocation JSON so we can assert the
 *     snake_case top-level keys (`contract_id`, `function`, `payload`)
 *     and per-function payload contents.
 *  2. **OkHttp transport behaviour** via [FakeOkHttpClient] — actual
 *     [OkHttpSepContractTransport] driven against a canned interceptor
 *     response, so we cover happy 2xx, 4xx with body, 5xx, and
 *     malformed body. No `MockWebServer`.
 *
 * Mirrors `SEPContractClientTests.swift` from onym-ios PR #24.
 */
class SepContractClientTest {

    private val testContractId = "C00000000000000000000000000000000000000000000000000000"

    // ─── wire-shape pins (fake transport) ──────────────────────────

    @Test
    fun createGroupV2_sendsSnakeCasePayload_andCorrectFunction() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """{"accepted":true,"transactionHash":"abc123"}""",
        )
        val client = SepContractClient(testContractId, transport)

        val request = SepCreateGroupV2Request(
            caller = "GBABCDEF",
            groupId = ByteArray(32) { 0xAB.toByte() },
            commitment = ByteArray(32) { 0xCD.toByte() },
            tier = SepTier.SMALL.rawValue.toUInt(),
            groupType = SepGroupType.TYRANNY,
            memberCount = 1u,
            proof = ByteArray(64) { 0xEE.toByte() },
            publicInputs = SepPublicInputs(
                commitment = ByteArray(32) { 0xCD.toByte() },
                epoch = 0uL,
            ),
        )
        val response = client.createGroupV2(request)
        assertTrue(response.accepted)
        assertEquals("abc123", response.transactionHash)

        val invocation = transport.lastInvocationJson
        assertNotNull(invocation)
        assertEquals(testContractId, invocation!!["contract_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals("create_group_v2", transport.lastFunction)

        val payload = transport.lastPayloadJson
        assertNotNull(payload)
        assertEquals("GBABCDEF", payload!!["caller"]!!.jsonPrimitive.contentOrNull)
        assertEquals(SepGroupType.TYRANNY.rawValue.toInt(), payload["group_type"]!!.jsonPrimitive.int)
        assertEquals(1, payload["member_count"]!!.jsonPrimitive.int)
        assertNotNull("group_id required", payload["group_id"])
        assertNotNull("public_inputs required", payload["public_inputs"])
    }

    @Test
    fun updateCommitment_routesToUpdateFunction_withSnakeCasePublicInputs() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """{"accepted":true}""",
        )
        val client = SepContractClient(testContractId, transport)

        val request = SepUpdateCommitmentRequest(
            groupId = ByteArray(32) { 0x01 },
            proof = ByteArray(32) { 0x02 },
            publicInputs = SepUpdatePublicInputs(
                cOld = ByteArray(32) { 0x03 },
                epochOld = 1uL,
                cNew = ByteArray(32) { 0x04 },
            ),
        )
        client.updateCommitment(request)

        assertEquals("update_commitment", transport.lastFunction)
        val payload = transport.lastPayloadJson!!
        val publicInputs = payload["public_inputs"] as kotlinx.serialization.json.JsonObject
        assertNotNull(publicInputs["c_old"])
        assertNotNull(publicInputs["c_new"])
        assertEquals(1L, publicInputs["epoch_old"]!!.jsonPrimitive.long)
    }

    @Test
    fun getState_sendsGroupIdInGetStateEnvelope() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """
                {"commitment":"CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
                 "epoch":7,"timestamp":1700000000,"tier":0,"active":true}
            """.trimIndent(),
        )
        val client = SepContractClient(testContractId, transport)

        val result = client.getState(groupId = ByteArray(32) { 0x55 })
        assertEquals(7uL, result.epoch)
        assertEquals(1_700_000_000uL, result.timestamp)
        assertEquals(0u, result.tier)
        assertTrue(result.active)
        assertArrayEquals(ByteArray(32) { 0x09 }, result.commitment)

        assertEquals("get_state", transport.lastFunction)
        assertNotNull(transport.lastPayloadJson!!["group_id"])
    }

    // ─── OkHttp transport behaviour ────────────────────────────────

    @Test
    fun okHttpTransport_returnsDecodedResponseOn2xx() = runTest {
        val canned = """{"accepted":true,"transactionHash":"deadbeef","message":"ok"}"""
        val httpClient = FakeOkHttpClient.build { req ->
            assertEquals("POST", req.method)
            // OkHttp may append `; charset=utf-8`; just check the prefix.
            assertTrue(req.body?.contentType().toString().startsWith("application/json"))
            FakeOkHttpClient.ok(req, canned)
        }
        val transport = OkHttpSepContractTransport(
            httpClient = httpClient,
            endpointUrl = "https://relayer.example/contract",
        )
        val client = SepContractClient(testContractId, transport)

        val response = client.createGroupV2(
            SepCreateGroupV2Request(
                caller = "GBABCDEF",
                groupId = ByteArray(32),
                commitment = ByteArray(32),
                tier = 0u,
                groupType = SepGroupType.TYRANNY,
                memberCount = 1u,
                proof = ByteArray(0),
                publicInputs = SepPublicInputs(commitment = ByteArray(32), epoch = 0uL),
            )
        )
        assertEquals("deadbeef", response.transactionHash)
        assertEquals("ok", response.message)
        assertTrue(response.accepted)
    }

    @Test
    fun okHttpTransport_throwsBadStatus_andCapturesBody() = runTest {
        val httpClient = FakeOkHttpClient.build { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("boom".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        val transport = OkHttpSepContractTransport(
            httpClient = httpClient,
            endpointUrl = "https://relayer.example/contract",
        )
        val client = SepContractClient(testContractId, transport)

        val thrown = assertThrows(SepContractError.BadStatus::class.java) {
            kotlinx.coroutines.runBlocking {
                client.getState(ByteArray(32))
            }
        }
        assertEquals(500, thrown.code)
        // Body is captured BEFORE the status check so the error path
        // can include it (gotcha #3 in the brief — `bytes()` is single-shot).
        assertEquals("boom", thrown.body)
    }

    @Test
    fun okHttpTransport_throwsBadStatusOn4xx() = runTest {
        val httpClient = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.status(req, 422, "Unprocessable Entity")
        }
        val transport = OkHttpSepContractTransport(httpClient, "https://r.example/c")
        val client = SepContractClient(testContractId, transport)

        val thrown = assertThrows(SepContractError.BadStatus::class.java) {
            kotlinx.coroutines.runBlocking { client.getState(ByteArray(32)) }
        }
        assertEquals(422, thrown.code)
    }

    @Test
    fun okHttpTransport_throwsMalformedResponse_onJunkBody() = runTest {
        val httpClient = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.ok(req, "not really json {{{")
        }
        val transport = OkHttpSepContractTransport(httpClient, "https://r.example/c")
        val client = SepContractClient(testContractId, transport)

        assertThrows(SepContractError.MalformedResponse::class.java) {
            kotlinx.coroutines.runBlocking {
                client.createGroupV2(
                    SepCreateGroupV2Request(
                        caller = "G",
                        groupId = ByteArray(32),
                        commitment = ByteArray(32),
                        tier = 0u,
                        groupType = SepGroupType.TYRANNY,
                        memberCount = 0u,
                        proof = ByteArray(0),
                        publicInputs = SepPublicInputs(ByteArray(32), 0uL),
                    )
                )
            }
        }
        Unit
    }

    @Test
    fun okHttpTransport_targetsConfiguredEndpointUrl() = runTest {
        var capturedUrl: String? = null
        val httpClient = FakeOkHttpClient.build { req ->
            capturedUrl = req.url.toString()
            FakeOkHttpClient.ok(req, """{"accepted":true}""")
        }
        val transport = OkHttpSepContractTransport(
            httpClient = httpClient,
            endpointUrl = "https://example.invalid/some/path",
        )
        val client = SepContractClient(testContractId, transport)

        client.updateCommitment(
            SepUpdateCommitmentRequest(
                groupId = ByteArray(32),
                proof = ByteArray(32),
                publicInputs = SepUpdatePublicInputs(ByteArray(32), 0uL, ByteArray(32)),
            )
        )
        assertEquals("https://example.invalid/some/path", capturedUrl)
    }

    @Test
    fun okHttpTransport_postsBodyAsContractInvocationEnvelope() = runTest {
        var capturedBodyJson: String? = null
        val httpClient = FakeOkHttpClient.build { req ->
            capturedBodyJson = req.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            FakeOkHttpClient.ok(req, """{"accepted":true}""")
        }
        val transport = OkHttpSepContractTransport(httpClient, "https://r.example/c")
        val client = SepContractClient(testContractId, transport)

        // Use updateCommitment so the canned `{"accepted":true}`
        // decodes as the expected SepSubmissionResponse — getState
        // would expect a SepCommitmentEntry shape we don't care about
        // for this body-shape pin.
        client.updateCommitment(
            SepUpdateCommitmentRequest(
                groupId = ByteArray(32) { 0x55 },
                proof = ByteArray(8),
                publicInputs = SepUpdatePublicInputs(ByteArray(32), 0uL, ByteArray(32)),
            )
        )

        val obj = Json.parseToJsonElement(capturedBodyJson!!) as kotlinx.serialization.json.JsonObject
        assertEquals(testContractId, obj["contract_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals("update_commitment", obj["function"]!!.jsonPrimitive.contentOrNull)
        assertNotNull(obj["payload"])
    }

    // ─── error sentinel sanity ─────────────────────────────────────

    @Test
    fun badStatus_messageContainsCodeAndBody() {
        val err = SepContractError.BadStatus(503, "service unavailable")
        assertTrue(err.message!!.contains("503"))
        assertTrue(err.message!!.contains("service unavailable"))
        // Identity check — sealed hierarchy is the public surface.
        assertSame(err, err)
    }
}
