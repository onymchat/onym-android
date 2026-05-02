package chat.onym.android.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Wire-format pin for the relayer JSON payloads. Every key tested
 * here matches `RelayerRequest` / `ContractType` / `Network` in
 * `onym-relayer/src/handler.rs` + `src/config.rs` byte-for-byte.
 *
 * Mirrors the `SEPContractTypes` round-trip cases from onym-ios
 * PR #27 (the rewrite that flipped the wire shape from the PR-A
 * baseline).
 */
class SepContractTypesTest {

    private val json = Json { encodeDefaults = true }

    // ─── enums ────────────────────────────────────────────────────

    @Test
    fun sepGroupType_serializesAsLowercaseString() {
        assertEquals(
            "\"tyranny\"",
            json.encodeToString(SepGroupType.serializer(), SepGroupType.TYRANNY),
        )
        assertEquals(
            "\"oneonone\"",
            json.encodeToString(SepGroupType.serializer(), SepGroupType.ONE_ON_ONE),
        )
    }

    @Test
    fun sepGroupType_decodesFromLowercaseString() {
        assertEquals(
            SepGroupType.TYRANNY,
            json.decodeFromString(SepGroupType.serializer(), "\"tyranny\""),
        )
        assertEquals(
            SepGroupType.OLIGARCHY,
            json.decodeFromString(SepGroupType.serializer(), "\"oligarchy\""),
        )
    }

    @Test
    fun sepNetwork_mainnetSerializesAsPublic() {
        assertEquals(
            "\"public\"",
            json.encodeToString(SepNetwork.serializer(), SepNetwork.PUBLIC_NET),
        )
        assertEquals(
            "\"testnet\"",
            json.encodeToString(SepNetwork.serializer(), SepNetwork.TESTNET),
        )
    }

    // ─── invocation envelope ──────────────────────────────────────

    @Test
    fun invocationEnvelope_topLevelKeysAreCamelCase() {
        val invocation = SepContractInvocation(
            contractID = "C12345",
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            function = "create_group",
            payload = JsonPrimitive("payload-stub"),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepContractInvocation.serializer(JsonPrimitive.serializer()), invocation),
        ).jsonObject
        assertEquals("C12345", obj["contractID"]!!.jsonPrimitive.contentOrNull)
        assertEquals("tyranny", obj["contractType"]!!.jsonPrimitive.contentOrNull)
        assertEquals("testnet", obj["network"]!!.jsonPrimitive.contentOrNull)
        assertEquals("create_group", obj["function"]!!.jsonPrimitive.contentOrNull)
        assertNotNull(obj["payload"])
        // No leftover snake_case keys from the previous shape.
        assertNull(obj["contract_id"])
        assertNull(obj["contract_type"])
    }

    @Test
    fun invocationEnvelope_mainnetSerializesPublicOnTheWire() {
        val invocation = SepContractInvocation(
            contractID = "C99999",
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.PUBLIC_NET,
            function = "create_group",
            payload = JsonPrimitive("p"),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepContractInvocation.serializer(JsonPrimitive.serializer()), invocation),
        ).jsonObject
        assertEquals("public", obj["network"]!!.jsonPrimitive.contentOrNull)
    }

    // ─── TyrannyCreateGroupPayload ────────────────────────────────

    @Test
    fun tyrannyCreateGroupPayload_emitsExpectedKeys() {
        val payload = TyrannyCreateGroupPayload(
            groupId = ByteArray(32) { 0xAB.toByte() },
            commitment = ByteArray(32) { 0xCD.toByte() },
            tier = 0,
            adminPubkeyCommitment = ByteArray(32) { 0xEE.toByte() },
            proof = ByteArray(1601) { 0x01 },
            publicInputs = listOf(
                ByteArray(32) { 0xCD.toByte() },
                ByteArray(32),
                ByteArray(32) { 0xEE.toByte() },
                ByteArray(32) { 0xFF.toByte() },
            ),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(TyrannyCreateGroupPayload.serializer(), payload),
        ).jsonObject

        assertNotNull("group_id required", obj["group_id"])
        assertNotNull("commitment required", obj["commitment"])
        assertEquals(0, obj["tier"]!!.jsonPrimitive.int)
        assertNotNull("admin_pubkey_commitment required", obj["admin_pubkey_commitment"])
        assertNotNull("proof required", obj["proof"])
        // PR #27 keeps publicInputs camelCase (matches iOS so the
        // field name lines up with the swift property).
        val pi = obj["publicInputs"] as JsonArray
        assertEquals(4, pi.size)
        for (element in pi) {
            val raw = Base64.getDecoder().decode(element.jsonPrimitive.content)
            assertEquals(32, raw.size)
        }

        // Round-trip preserves the proof bytes exactly.
        val proofBytes = Base64.getDecoder().decode(obj["proof"]!!.jsonPrimitive.content)
        assertEquals(1601, proofBytes.size)
        assertArrayEquals(payload.proof, proofBytes)
    }

    @Test
    fun tyrannyCreateGroupPayload_roundtripPreservesAllFields() {
        val original = TyrannyCreateGroupPayload(
            groupId = ByteArray(32) { 0x11 },
            commitment = ByteArray(32) { 0x22 },
            tier = 1,
            adminPubkeyCommitment = ByteArray(32) { 0x33 },
            proof = ByteArray(1601) { 0x44 },
            publicInputs = (0 until 4).map { ByteArray(32) { (it + 1).toByte() } },
        )
        val encoded = json.encodeToString(TyrannyCreateGroupPayload.serializer(), original)
        val decoded = json.decodeFromString(TyrannyCreateGroupPayload.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ─── TyrannyUpdateCommitmentPayload ───────────────────────────

    @Test
    fun tyrannyUpdateCommitmentPayload_roundtripPreservesAllFields() {
        val original = TyrannyUpdateCommitmentPayload(
            groupId = ByteArray(32) { 0x01 },
            proof = ByteArray(1601) { 0x02 },
            publicInputs = (0 until 5).map { ByteArray(32) { (it + 10).toByte() } },
        )
        val encoded = json.encodeToString(TyrannyUpdateCommitmentPayload.serializer(), original)
        val decoded = json.decodeFromString(TyrannyUpdateCommitmentPayload.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ─── GetCommitmentPayload ─────────────────────────────────────

    @Test
    fun getCommitmentPayload_emitsGroupIdSnakeCase() {
        val obj = json.parseToJsonElement(
            json.encodeToString(
                GetCommitmentPayload.serializer(),
                GetCommitmentPayload(groupId = ByteArray(32) { 0x55 }),
            ),
        ).jsonObject
        assertNotNull("group_id required", obj["group_id"])
        assertNull(obj["groupId"])
    }

    // ─── responses ────────────────────────────────────────────────

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

    @Test
    fun submissionResponse_keepsCamelCaseTransactionHashKey() {
        val payload = SepSubmissionResponse(
            accepted = true,
            transactionHash = "abc123",
            message = "ok",
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(SepSubmissionResponse.serializer(), payload),
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

    @Suppress("unused")
    private fun JsonObject.longField(key: String): Long = this[key]!!.jsonPrimitive.long
}
