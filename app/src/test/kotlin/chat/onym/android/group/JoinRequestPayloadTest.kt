package chat.onym.android.group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JoinRequestPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun roundtrip_preservesAllFields() {
        val original = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0xAA.toByte() },
            joinerDisplayLabel = "Bob",
            groupId = ByteArray(32) { 0x42 },
        )
        val encoded = json.encodeToString(JoinRequestPayload.serializer(), original)
        val decoded = json.decodeFromString(JoinRequestPayload.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun snake_case_keys_match_iOS_parity() {
        // Cross-platform interop pin — iOS will use JSONEncoder with
        // explicit CodingKeys; the Android side must emit the same
        // snake_case spelling so a Swift JSONDecoder can pick it up.
        val payload = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32),
            joinerDisplayLabel = "Bob",
            groupId = ByteArray(32),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(JoinRequestPayload.serializer(), payload),
        ).jsonObject
        assertNotNull(obj["joiner_inbox_pub"])
        assertNotNull(obj["joiner_display_label"])
        assertNotNull(obj["group_id"])
        assertEquals("Bob", obj["joiner_display_label"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun decodes_pre_pr78_payload_without_joiner_bls_pub() {
        // Pre-PR-78 joiner — no joiner_bls_pub field. Must still decode.
        val text = """
            {
              "joiner_inbox_pub": "${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}",
              "joiner_display_label": "Alice",
              "group_id": "${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(JoinRequestPayload.serializer(), text)
        assertEquals(null, decoded.joinerBlsPublicKey)
        assertEquals("Alice", decoded.joinerDisplayLabel)
    }

    @Test
    fun decodes_post_pr78_payload_with_joiner_bls_pub() {
        val bls = ByteArray(48) { 0xBB.toByte() }
        val text = """
            {
              "joiner_inbox_pub": "${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}",
              "joiner_bls_pub": "${java.util.Base64.getEncoder().encodeToString(bls)}",
              "joiner_display_label": "Alice",
              "group_id": "${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(JoinRequestPayload.serializer(), text)
        assertEquals(48, decoded.joinerBlsPublicKey!!.size)
    }

    @Test
    fun constructor_rejectsWrongSizedBlsKey() {
        assertThrows(IllegalArgumentException::class.java) {
            JoinRequestPayload(
                joinerInboxPublicKey = ByteArray(32),
                joinerBlsPublicKey = ByteArray(47),
                joinerDisplayLabel = "x",
                groupId = ByteArray(32),
            )
        }
    }

    @Test
    fun constructor_rejectsWrongSizedKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            JoinRequestPayload(
                joinerInboxPublicKey = ByteArray(31),
                joinerDisplayLabel = "x",
                groupId = ByteArray(32),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JoinRequestPayload(
                joinerInboxPublicKey = ByteArray(32),
                joinerDisplayLabel = "x",
                groupId = ByteArray(33),
            )
        }
    }
}
