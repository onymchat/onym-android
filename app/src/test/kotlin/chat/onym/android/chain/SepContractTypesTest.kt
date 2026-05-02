package chat.onym.android.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Wire-format pin for the chain JSON payloads. Each request/response
 * struct round-trips losslessly AND emits the snake_case keys the
 * iOS twin (`SEPContractTypes.swift`) writes — both are load-bearing
 * for cross-platform interop with the relayer.
 *
 * Mirrors the Codable round-trip + JSON-shape assertions inlined
 * across `SEPContractClientTests.swift` from onym-ios PR #24.
 */
class SepContractTypesTest {

    private val json = Json { encodeDefaults = true }

    // ─── enum ─────────────────────────────────────────────────────

    @Test
    fun sepGroupType_serializesAsRawUInt32() {
        val tyrannyJson = json.encodeToString(SepGroupType.serializer(), SepGroupType.TYRANNY)
        assertEquals("4", tyrannyJson)
        val anarchyJson = json.encodeToString(SepGroupType.serializer(), SepGroupType.ANARCHY)
        assertEquals("0", anarchyJson)
    }

    @Test
    fun sepGroupType_decodesFromRawInt() {
        assertEquals(SepGroupType.TYRANNY, json.decodeFromString(SepGroupType.serializer(), "4"))
        assertEquals(SepGroupType.OLIGARCHY, json.decodeFromString(SepGroupType.serializer(), "3"))
    }

    // ─── public-input bundles ─────────────────────────────────────

    @Test
    fun sepPublicInputs_roundtripPreservesFields() {
        val original = SepPublicInputs(
            commitment = ByteArray(32) { 0xAB.toByte() },
            epoch = 7uL,
        )
        val encoded = json.encodeToString(SepPublicInputs.serializer(), original)
        val decoded = json.decodeFromString(SepPublicInputs.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun sepUpdatePublicInputs_emitsSnakeCaseKeys() {
        val payload = SepUpdatePublicInputs(
            cOld = ByteArray(32) { 0x01 },
            epochOld = 1uL,
            cNew = ByteArray(32) { 0x02 },
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepUpdatePublicInputs.serializer(), payload)
        ).jsonObject
        assertNotNull("c_old key required", obj["c_old"])
        assertNotNull("c_new key required", obj["c_new"])
        assertEquals(1L, obj["epoch_old"]!!.jsonPrimitive.long)
        assertNull("plain `cOld` must NOT appear", obj["cOld"])
        assertNull("plain `cNew` must NOT appear", obj["cNew"])
    }

    // ─── create_group_v2 payload ──────────────────────────────────

    @Test
    fun createGroupV2Request_roundtripPreservesAllFields() {
        val original = SepCreateGroupV2Request(
            caller = "GBABCDEF",
            groupId = ByteArray(32) { 0xAB.toByte() },
            commitment = ByteArray(32) { 0xCD.toByte() },
            tier = 0u,
            groupType = SepGroupType.TYRANNY,
            memberCount = 1u,
            proof = ByteArray(64) { 0xEE.toByte() },
            publicInputs = SepPublicInputs(
                commitment = ByteArray(32) { 0xCD.toByte() },
                epoch = 0uL,
            ),
        )
        val encoded = json.encodeToString(SepCreateGroupV2Request.serializer(), original)
        val decoded = json.decodeFromString(SepCreateGroupV2Request.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun createGroupV2Request_emitsSnakeCaseKeys() {
        val request = SepCreateGroupV2Request(
            caller = "GBABCDEF",
            groupId = ByteArray(32) { 0xAB.toByte() },
            commitment = ByteArray(32) { 0xCD.toByte() },
            tier = 0u,
            groupType = SepGroupType.TYRANNY,
            memberCount = 3u,
            proof = ByteArray(8) { 0xEE.toByte() },
            publicInputs = SepPublicInputs(
                commitment = ByteArray(32) { 0xCD.toByte() },
                epoch = 0uL,
            ),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepCreateGroupV2Request.serializer(), request)
        ).jsonObject

        // Snake-case key checks — iOS emits these via explicit
        // CodingKeys. Drift here breaks the relayer.
        assertNotNull("group_id required", obj["group_id"])
        assertEquals(0, obj["tier"]!!.jsonPrimitive.int)
        assertEquals(4, obj["group_type"]!!.jsonPrimitive.int)
        assertEquals(3, obj["member_count"]!!.jsonPrimitive.int)
        assertNotNull("public_inputs required", obj["public_inputs"])
        assertNull("plain `groupId` must NOT appear", obj["groupId"])
        assertNull("plain `groupType` must NOT appear", obj["groupType"])
        assertNull("plain `memberCount` must NOT appear", obj["memberCount"])

        // ByteArray fields encode as base64 strings — pin one to lock
        // the wire shape against accidental "JSON int array" drift.
        val groupIdEncoded = obj["group_id"]!!.jsonPrimitive.contentOrNull
        assertNotNull(groupIdEncoded)
        assertTrue(
            "group_id must round-trip as base64 of the original bytes",
            Base64.getDecoder().decode(groupIdEncoded!!).contentEquals(request.groupId),
        )
    }

    // ─── update_commitment payload ────────────────────────────────

    @Test
    fun updateCommitmentRequest_roundtripPreservesAllFields() {
        val original = SepUpdateCommitmentRequest(
            groupId = ByteArray(32) { 0x01 },
            proof = ByteArray(32) { 0x02 },
            publicInputs = SepUpdatePublicInputs(
                cOld = ByteArray(32) { 0x03 },
                epochOld = 1uL,
                cNew = ByteArray(32) { 0x04 },
            ),
        )
        val encoded = json.encodeToString(SepUpdateCommitmentRequest.serializer(), original)
        val decoded = json.decodeFromString(SepUpdateCommitmentRequest.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ─── get_state ────────────────────────────────────────────────

    @Test
    fun getStateRequest_emitsGroupIdSnakeCase() {
        val obj = json.parseToJsonElement(
            json.encodeToString(
                SepGetStateRequest.serializer(),
                SepGetStateRequest(groupId = ByteArray(32) { 0x55 }),
            )
        ).jsonObject
        assertNotNull("group_id required", obj["group_id"])
        assertNull(obj["groupId"])
    }

    @Test
    fun commitmentEntry_roundtripPreservesAllFields() {
        val original = SepCommitmentEntry(
            commitment = ByteArray(32) { 0x09 },
            epoch = 7uL,
            timestamp = 1_700_000_000uL,
            tier = 0u,
            active = true,
        )
        val encoded = json.encodeToString(SepCommitmentEntry.serializer(), original)
        val decoded = json.decodeFromString(SepCommitmentEntry.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ─── submission response ──────────────────────────────────────

    @Test
    fun submissionResponse_keepsCamelCaseTransactionHashKey() {
        // **Load-bearing quirk**: iOS doesn't override the CodingKey
        // for `transactionHash`, so the JSON key stays camelCase
        // (NOT `transaction_hash`). Pin the Android side to the same
        // shape — relayer parsing depends on it.
        val payload = SepSubmissionResponse(
            accepted = true,
            transactionHash = "abc123",
            message = "ok",
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepSubmissionResponse.serializer(), payload)
        ).jsonObject
        assertEquals("abc123", obj["transactionHash"]!!.jsonPrimitive.contentOrNull)
        assertNull("snake-case `transaction_hash` must NOT appear", obj["transaction_hash"])
    }

    @Test
    fun submissionResponse_decodesNullableFields() {
        val raw = """{"accepted":true}"""
        val decoded = json.decodeFromString(SepSubmissionResponse.serializer(), raw)
        assertTrue(decoded.accepted)
        assertNull(decoded.transactionHash)
        assertNull(decoded.message)
    }

    // ─── invocation envelope ──────────────────────────────────────

    @Test
    fun invocationEnvelope_topLevelKeysAreContractIdFunctionPayload() {
        val invocation = SepContractInvocation(
            contractId = "C12345",
            function = "create_group_v2",
            payload = JsonPrimitive("payload-stub"),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepContractInvocation.serializer(JsonPrimitive.serializer()), invocation)
        ).jsonObject
        assertEquals("C12345", obj["contract_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals("create_group_v2", obj["function"]!!.jsonPrimitive.contentOrNull)
        assertNotNull(obj["payload"])
        assertNull("plain `contractId` must NOT appear", obj["contractId"])
    }

    // ─── helper assertion ────────────────────────────────────────

    @Suppress("unused")
    private fun JsonObject.boolField(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
}
