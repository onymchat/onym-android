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

    // ─── OneOnOneCreateGroupPayload ───────────────────────────────

    @Test
    fun oneOnOneCreateGroupPayload_emitsExpectedKeys() {
        val payload = OneOnOneCreateGroupPayload(
            groupId = ByteArray(32) { 0xAB.toByte() },
            commitment = ByteArray(32) { 0xCD.toByte() },
            proof = ByteArray(1601) { 0x01 },
            publicInputs = listOf(
                ByteArray(32) { 0xCD.toByte() },
                ByteArray(32),
            ),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(OneOnOneCreateGroupPayload.serializer(), payload),
        ).jsonObject

        assertNotNull("group_id required", obj["group_id"])
        assertNotNull("commitment required", obj["commitment"])
        assertNotNull("proof required", obj["proof"])
        // No `tier` and no `admin_pubkey_commitment` — OneOnOne is
        // depth-5 hardcoded with no admin role.
        assertNull("tier must NOT appear", obj["tier"])
        assertNull("admin_pubkey_commitment must NOT appear", obj["admin_pubkey_commitment"])

        val pi = obj["publicInputs"] as JsonArray
        assertEquals("OneOnOne PI is 2 × 32B (commitment, fr_zero)", 2, pi.size)
        for (element in pi) {
            val raw = Base64.getDecoder().decode(element.jsonPrimitive.content)
            assertEquals(32, raw.size)
        }

        val proofBytes = Base64.getDecoder().decode(obj["proof"]!!.jsonPrimitive.content)
        assertEquals(1601, proofBytes.size)
        assertArrayEquals(payload.proof, proofBytes)
    }

    @Test
    fun oneOnOneCreateGroupPayload_roundtripPreservesAllFields() {
        val original = OneOnOneCreateGroupPayload(
            groupId = ByteArray(32) { 0x11 },
            commitment = ByteArray(32) { 0x22 },
            proof = ByteArray(1601) { 0x33 },
            publicInputs = listOf(
                ByteArray(32) { 0x22 },
                ByteArray(32),
            ),
        )
        val encoded = json.encodeToString(OneOnOneCreateGroupPayload.serializer(), original)
        val decoded = json.decodeFromString(OneOnOneCreateGroupPayload.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ─── AnarchyCreateGroupPayload ────────────────────────────────

    @Test
    fun anarchyCreateGroupPayload_emitsExpectedKeys() {
        val payload = AnarchyCreateGroupPayload(
            groupId = ByteArray(32) { 0x10 },
            commitment = ByteArray(32) { 0x20 },
            tier = 0,
            memberCount = 1,
            proof = ByteArray(1601) { 0x30 },
            publicInputs = listOf(
                ByteArray(32) { 0x20 },
                ByteArray(32),
            ),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(AnarchyCreateGroupPayload.serializer(), payload),
        ).jsonObject

        assertNotNull("group_id required", obj["group_id"])
        assertNotNull("commitment required", obj["commitment"])
        assertEquals(0, obj["tier"]!!.jsonPrimitive.int)
        // Snake-case `member_count` per the relayer's
        // `MEMBER_COUNT_KEYS` lookup.
        assertEquals(1, obj["member_count"]!!.jsonPrimitive.int)
        assertNotNull("proof required", obj["proof"])
        // No `admin_pubkey_commitment` — Anarchy has no admin role.
        assertNull("admin_pubkey_commitment must NOT appear", obj["admin_pubkey_commitment"])

        val pi = obj["publicInputs"] as JsonArray
        assertEquals("Anarchy PI is 2 × 32B (commitment, fr_zero)", 2, pi.size)
        for (element in pi) {
            val raw = Base64.getDecoder().decode(element.jsonPrimitive.content)
            assertEquals(32, raw.size)
        }

        val proofBytes = Base64.getDecoder().decode(obj["proof"]!!.jsonPrimitive.content)
        assertEquals(1601, proofBytes.size)
        assertArrayEquals(payload.proof, proofBytes)
    }

    @Test
    fun anarchyCreateGroupPayload_roundtripPreservesAllFields() {
        val original = AnarchyCreateGroupPayload(
            groupId = ByteArray(32) { 0x44 },
            commitment = ByteArray(32) { 0x55 },
            tier = 2,
            memberCount = 0,
            proof = ByteArray(1601) { 0x66 },
            publicInputs = listOf(ByteArray(32) { 0x55 }, ByteArray(32)),
        )
        val encoded = json.encodeToString(AnarchyCreateGroupPayload.serializer(), original)
        val decoded = json.decodeFromString(AnarchyCreateGroupPayload.serializer(), encoded)
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
    fun commitmentEntry_decodesTyrannyShape_withoutActive() {
        // Tyranny `get_commitment` returns commitment + epoch + timestamp
        // + tier — but NOT `active` (that's democracy/oligarchy only).
        // Release run #25271977084 surfaced exactly this: the relayer
        // omitted `active` and the decoder threw `MissingFieldException`.
        // See SepCommitmentEntry's per-governance table for the full
        // shape matrix.
        val tyrannyResponse = """
            {"commitment":"CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
             "epoch":0,"timestamp":1700000000,"tier":0}
        """.trimIndent()
        val decoded = json.decodeFromString(SepCommitmentEntry.serializer(), tyrannyResponse)
        assertEquals(0uL, decoded.epoch)
        assertEquals(0u, decoded.tier)
        assertEquals(1_700_000_000uL, decoded.timestamp)
        assertNull("tyranny doesn't ship `active`", decoded.active)
    }

    @Test
    fun commitmentEntry_decodesMinimalShape_commitmentEpochOnly() {
        // The "every variant" floor — anarchy in some configurations
        // ships only commitment + epoch. Decoder must tolerate it.
        val minimalResponse = """
            {"commitment":"CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
             "epoch":3}
        """.trimIndent()
        val decoded = json.decodeFromString(SepCommitmentEntry.serializer(), minimalResponse)
        assertEquals(3uL, decoded.epoch)
        assertNull(decoded.timestamp)
        assertNull(decoded.tier)
        assertNull(decoded.active)
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
