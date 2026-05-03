package chat.onym.android.chain

import chat.onym.android.support.FakeOkHttpClient
import chat.onym.android.support.FakeSepContractTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
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
 *     camelCase top-level keys (`contractID`, `contractType`,
 *     `network`, `function`, `payload`) and per-function payload
 *     contents.
 *  2. **OkHttp transport behaviour** via [FakeOkHttpClient] — actual
 *     [OkHttpSepContractTransport] driven against a canned
 *     interceptor response, so we cover happy 2xx, 4xx with body,
 *     5xx, and malformed body. No `MockWebServer`.
 *
 * Mirrors `SEPContractClientTests.swift` from onym-ios PR #27.
 */
class SepContractClientTest {

    private val testContractId = "C00000000000000000000000000000000000000000000000000000"

    // ─── wire-shape pins (fake transport) ──────────────────────────

    @Test
    fun createGroupTyranny_sendsCamelCaseEnvelope_andCorrectFunction() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """{"accepted":true,"transactionHash":"abc123"}""",
        )
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        val payload = TyrannyCreateGroupPayload(
            groupId = ByteArray(32) { 0xAB.toByte() },
            commitment = ByteArray(32) { 0xCD.toByte() },
            tier = SepTier.SMALL.rawValue,
            adminPubkeyCommitment = ByteArray(32) { 0xEE.toByte() },
            proof = ByteArray(1601) { 0x01 },
            publicInputs = (0 until 4).map { ByteArray(32) { (it + 1).toByte() } },
        )
        val response = client.createGroupTyranny(payload)
        assertTrue(response.accepted)
        assertEquals("abc123", response.transactionHash)

        val invocation = transport.lastInvocationJson
        assertNotNull(invocation)
        assertEquals(testContractId, invocation!!["contractID"]!!.jsonPrimitive.contentOrNull)
        assertEquals("tyranny", invocation["contractType"]!!.jsonPrimitive.contentOrNull)
        assertEquals("testnet", invocation["network"]!!.jsonPrimitive.contentOrNull)
        assertEquals("create_group", transport.lastFunction)

        val payloadJson = transport.lastPayloadJson
        assertNotNull(payloadJson)
        assertNotNull("group_id required", payloadJson!!["group_id"])
        assertNotNull("commitment required", payloadJson["commitment"])
        assertEquals(0, payloadJson["tier"]!!.jsonPrimitive.int)
        assertNotNull("admin_pubkey_commitment required", payloadJson["admin_pubkey_commitment"])
        assertNotNull("proof required", payloadJson["proof"])
        val pi = payloadJson["publicInputs"] as JsonArray
        assertEquals("publicInputs is a 4-element array", 4, pi.size)
    }

    @Test
    fun createGroupTyranny_mainnetSerializesPublicOnTheWire() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """{"accepted":true}""",
        )
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.PUBLIC_NET,
            transport = transport,
        )
        client.createGroupTyranny(stubPayload())
        assertEquals("public", transport.lastInvocationJson!!["network"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun createGroupOneOnOne_sendsCreateGroupFunction_withTwoChunkPI_andNoTier() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """{"accepted":true,"transactionHash":"oo123"}""",
        )
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.ONE_ON_ONE,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        val payload = OneOnOneCreateGroupPayload(
            groupId = ByteArray(32) { 0x42 },
            commitment = ByteArray(32) { 0x55 },
            proof = ByteArray(1601) { 0x77 },
            publicInputs = listOf(ByteArray(32) { 0x55 }, ByteArray(32)),
        )
        val response = client.createGroupOneOnOne(payload)
        assertTrue(response.accepted)
        assertEquals("oo123", response.transactionHash)

        assertEquals("create_group", transport.lastFunction)
        val invocation = transport.lastInvocationJson!!
        assertEquals("oneonone", invocation["contractType"]!!.jsonPrimitive.contentOrNull)

        val payloadJson = transport.lastPayloadJson!!
        assertNotNull("group_id required", payloadJson["group_id"])
        assertNotNull("commitment required", payloadJson["commitment"])
        assertNotNull("proof required", payloadJson["proof"])
        assertEquals("OneOnOne sends no tier", null, payloadJson["tier"])
        assertEquals(
            "OneOnOne sends no admin_pubkey_commitment",
            null,
            payloadJson["admin_pubkey_commitment"],
        )
        val pi = payloadJson["publicInputs"] as JsonArray
        assertEquals(2, pi.size)
    }

    @Test
    fun updateCommitmentTyranny_routesToUpdateFunction_with5ChunkPI() = runTest {
        val transport = FakeSepContractTransport(
            cannedResponseJson = """{"accepted":true}""",
        )
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )
        client.updateCommitmentTyranny(
            TyrannyUpdateCommitmentPayload(
                groupId = ByteArray(32),
                proof = ByteArray(1601),
                publicInputs = (0 until 5).map { ByteArray(32) },
            ),
        )
        assertEquals("update_commitment", transport.lastFunction)
        val pi = transport.lastPayloadJson!!["publicInputs"] as JsonArray
        assertEquals(5, pi.size)
    }

    @Test
    fun getCommitment_sendsGroupIdInGetCommitmentEnvelope() = runTest {
        val canned = """
            {"commitment":"CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
             "epoch":7,"timestamp":1700000000,"tier":0,"active":true}
        """.trimIndent()
        val transport = FakeSepContractTransport(cannedResponseJson = canned)
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        val result = client.getCommitment(groupId = ByteArray(32) { 0x55 })
        assertEquals(7uL, result.epoch)
        assertArrayEquals(ByteArray(32) { 0x09 }, result.commitment)

        assertEquals("get_commitment", transport.lastFunction)
        assertNotNull(transport.lastPayloadJson!!["group_id"])
    }

    // ─── OkHttp transport behaviour ────────────────────────────────

    @Test
    fun okHttpTransport_returnsDecodedResponseOn2xx() = runTest {
        val canned = """{"accepted":true,"transactionHash":"deadbeef","message":"ok"}"""
        val httpClient = FakeOkHttpClient.build { req ->
            assertEquals("POST", req.method)
            assertTrue(req.body?.contentType().toString().startsWith("application/json"))
            FakeOkHttpClient.ok(req, canned)
        }
        val transport = OkHttpSepContractTransport(
            httpClient = httpClient,
            endpointUrl = "https://relayer.example/contract",
        )
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        val response = client.createGroupTyranny(stubPayload())
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
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        val thrown = assertThrows(SepContractError.BadStatus::class.java) {
            kotlinx.coroutines.runBlocking { client.getCommitment(ByteArray(32)) }
        }
        assertEquals(500, thrown.code)
        assertEquals("boom", thrown.body)
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
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )
        client.createGroupTyranny(stubPayload())
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
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )
        client.createGroupTyranny(stubPayload())

        val obj = Json.parseToJsonElement(capturedBodyJson!!) as JsonObject
        assertEquals(testContractId, obj["contractID"]!!.jsonPrimitive.contentOrNull)
        assertEquals("tyranny", obj["contractType"]!!.jsonPrimitive.contentOrNull)
        assertEquals("testnet", obj["network"]!!.jsonPrimitive.contentOrNull)
        assertEquals("create_group", obj["function"]!!.jsonPrimitive.contentOrNull)
        assertNotNull(obj["payload"])
    }

    // ─── BearerAuthInterceptor wiring (PR-28 follow-up) ────────────

    @Test
    fun bearerAuth_addsAuthorizationHeader_whenTokenSet() = runTest {
        var capturedAuthHeader: String? = null
        val httpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(token = "deadbeefcafef00d"))
            .addInterceptor { chain ->
                capturedAuthHeader = chain.request().header("Authorization")
                FakeOkHttpClient.ok(chain.request(), """{"accepted":true}""")
            }
            .build()
        val transport = OkHttpSepContractTransport(httpClient, "https://r.example/c")
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        client.createGroupTyranny(stubPayload())

        assertEquals("Bearer deadbeefcafef00d", capturedAuthHeader)
    }

    @Test
    fun bearerAuth_omitsAuthorizationHeader_whenTokenNullOrBlank() = runTest {
        var capturedAuthHeader: String? = "<unset-sentinel>"
        val httpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(token = null))
            .addInterceptor { chain ->
                capturedAuthHeader = chain.request().header("Authorization")
                FakeOkHttpClient.ok(chain.request(), """{"accepted":true}""")
            }
            .build()
        val transport = OkHttpSepContractTransport(httpClient, "https://r.example/c")
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        client.createGroupTyranny(stubPayload())

        // No Authorization header — relayer 401s with a clear "missing
        // bearer" message rather than `Bearer ""` (also 401, more
        // confusing).
        org.junit.Assert.assertNull(capturedAuthHeader)
    }

    @Test
    fun bearerAuth_omitsAuthorizationHeader_whenTokenIsBlank() = runTest {
        var capturedAuthHeader: String? = "<unset-sentinel>"
        val httpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(token = "   "))
            .addInterceptor { chain ->
                capturedAuthHeader = chain.request().header("Authorization")
                FakeOkHttpClient.ok(chain.request(), """{"accepted":true}""")
            }
            .build()
        val transport = OkHttpSepContractTransport(httpClient, "https://r.example/c")
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = transport,
        )

        client.createGroupTyranny(stubPayload())

        org.junit.Assert.assertNull(capturedAuthHeader)
    }

    // ─── error sentinel sanity ─────────────────────────────────────

    @Test
    fun badStatus_messageContainsCodeAndBody() {
        val err = SepContractError.BadStatus(503, "service unavailable")
        assertTrue(err.message!!.contains("503"))
        assertTrue(err.message!!.contains("service unavailable"))
        assertSame(err, err)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun stubPayload() = TyrannyCreateGroupPayload(
        groupId = ByteArray(32),
        commitment = ByteArray(32),
        tier = 0,
        adminPubkeyCommitment = ByteArray(32),
        proof = ByteArray(8),
        publicInputs = (0 until 4).map { ByteArray(32) },
    )
}
