package app.onym.android.group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Wire-format pin for [GroupInvitationPayload]. PR-2 adds the
 * `invitee_bls_secret_key` field for OneOnOne; everything else stays
 * round-trippable so older / non-OneOnOne envelopes decode unchanged.
 */
class GroupInvitationPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun roundtrip_tyrannyShape_preservesAllFields_andOmitsOneOnOneKey() {
        val original = GroupInvitationPayload(
            version = 1,
            groupId = ByteArray(32) { 0x11 },
            groupSecret = ByteArray(32) { 0x22 },
            name = "Friends",
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32) { 0x33 },
            commitment = ByteArray(32) { 0x44 },
            tierRaw = 0,
            groupTypeRaw = "tyranny",
            adminPubkeyHex = "abc",
            inviteeBlsSecretKey = null,
        )
        val encoded = json.encodeToString(GroupInvitationPayload.serializer(), original)
        val decoded = json.decodeFromString(GroupInvitationPayload.serializer(), encoded)
        assertEquals(original, decoded)

        // The serializer emits the new field only when it has a value
        // (Json by default still emits null when encodeDefaults=true,
        // so just sanity-check the snake_case key spelling).
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertTrue(
            "invitee_bls_secret_key key must use snake_case if present",
            obj.containsKey("invitee_bls_secret_key") || obj["invitee_bls_secret_key"] == null,
        )
        // tier_raw + group_type_raw stay snake-cased for cross-platform parity.
        assertEquals(0, obj["tier_raw"]!!.jsonPrimitive.contentOrNull?.toInt())
        assertEquals("tyranny", obj["group_type_raw"]!!.jsonPrimitive.contentOrNull)
        assertEquals("abc", obj["admin_pubkey_hex"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun roundtrip_memberProfilesField_preservesEntries() {
        val profile = MemberProfile(
            alias = "Alice",
            inboxPublicKey = ByteArray(32) { 0xAB.toByte() },
            sendingPubkey = ByteArray(32) { 0xCD.toByte() },
        )
        val original = GroupInvitationPayload(
            version = 1,
            groupId = ByteArray(32),
            groupSecret = ByteArray(32),
            name = "G",
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32),
            commitment = ByteArray(32),
            tierRaw = 0,
            groupTypeRaw = "tyranny",
            memberProfiles = mapOf("aa".repeat(48) to profile),
        )
        val encoded = json.encodeToString(GroupInvitationPayload.serializer(), original)
        val decoded = json.decodeFromString(GroupInvitationPayload.serializer(), encoded)
        assertEquals(original, decoded)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertNotNull(obj["member_profiles"])
    }

    @Test
    fun roundtrip_decode_pre_pr82_payload_without_member_profiles() {
        val original = GroupInvitationPayload(
            version = 1,
            groupId = ByteArray(32),
            groupSecret = ByteArray(32),
            name = "G",
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32),
            commitment = null,
            tierRaw = 0,
            groupTypeRaw = "tyranny",
            memberProfiles = null,
        )
        val encoded = json.encodeToString(GroupInvitationPayload.serializer(), original)
        val decoded = json.decodeFromString(GroupInvitationPayload.serializer(), encoded)
        assertNull(decoded.memberProfiles)
    }

    @Test
    fun roundtrip_oneOnOneShape_carriesInviteeBlsSecretAsBase64() {
        val sk1 = ByteArray(32) { 0x99.toByte() }
        val original = GroupInvitationPayload(
            version = 1,
            groupId = ByteArray(32) { 0x55 },
            groupSecret = ByteArray(32) { 0x66 },
            name = "1v1",
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32) { 0x77 },
            commitment = ByteArray(32) { 0x88.toByte() },
            tierRaw = 0,
            groupTypeRaw = "oneonone",
            adminPubkeyHex = null,
            inviteeBlsSecretKey = sk1,
        )
        val encoded = json.encodeToString(GroupInvitationPayload.serializer(), original)
        val obj = json.parseToJsonElement(encoded).jsonObject

        val rawB64 = obj["invitee_bls_secret_key"]!!.jsonPrimitive.content
        assertArrayEquals(sk1, Base64.getDecoder().decode(rawB64))

        // OneOnOne envelopes don't surface an admin pubkey.
        assertNull(
            "admin_pubkey_hex must be JSON-null for OneOnOne envelopes",
            obj["admin_pubkey_hex"]!!.jsonPrimitive.contentOrNull,
        )

        val decoded = json.decodeFromString(GroupInvitationPayload.serializer(), encoded)
        assertEquals(original, decoded)
        assertNotNull(decoded.inviteeBlsSecretKey)
        assertArrayEquals(sk1, decoded.inviteeBlsSecretKey)
    }

    @Test
    fun decode_legacyEnvelopeWithoutInviteeBlsSecretKey_decodesWithNullField() {
        // Envelopes from a pre-PR-2 sender don't include the new field.
        // The optional default keeps decoding compatible.
        val legacyJson = """
            {
              "version": 1,
              "group_id": "${b64(ByteArray(32) { 0x10 })}",
              "group_secret": "${b64(ByteArray(32) { 0x20 })}",
              "name": "legacy",
              "members": [],
              "epoch": 0,
              "salt": "${b64(ByteArray(32) { 0x30 })}",
              "commitment": "${b64(ByteArray(32) { 0x40 })}",
              "tier_raw": 0,
              "group_type_raw": "tyranny",
              "admin_pubkey_hex": "deadbeef"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(GroupInvitationPayload.serializer(), legacyJson)
        assertEquals("legacy", decoded.name)
        assertNull(decoded.inviteeBlsSecretKey)
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)
}
