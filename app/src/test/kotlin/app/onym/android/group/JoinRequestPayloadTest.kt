package app.onym.android.group

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalSerializationApi::class)
class JoinRequestPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun roundtrip_preservesAllFields() {
        val original = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0xAA.toByte() },
            joinerSendingPublicKey = ByteArray(32) { 0xBB.toByte() },
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
            joinerSendingPublicKey = ByteArray(32),
            joinerDisplayLabel = "Bob",
            groupId = ByteArray(32),
        )
        val obj = json.parseToJsonElement(
            json.encodeToString(JoinRequestPayload.serializer(), payload),
        ).jsonObject
        assertNotNull(obj["joiner_inbox_pub"])
        assertNotNull(obj["joiner_sending_pub"])
        assertNotNull(obj["joiner_display_label"])
        assertNotNull(obj["group_id"])
        assertEquals("Bob", obj["joiner_display_label"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun decodes_payload_without_joiner_bls_pub() {
        // joiner_bls_pub stays optional (separate gate); the test is
        // updated to ship joiner_sending_pub, which IS required.
        val text = """
            {
              "joiner_inbox_pub": "${b64(ByteArray(32))}",
              "joiner_sending_pub": "${b64(ByteArray(32))}",
              "joiner_display_label": "Alice",
              "group_id": "${b64(ByteArray(32))}"
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
              "joiner_inbox_pub": "${b64(ByteArray(32))}",
              "joiner_bls_pub": "${b64(bls)}",
              "joiner_sending_pub": "${b64(ByteArray(32))}",
              "joiner_display_label": "Alice",
              "group_id": "${b64(ByteArray(32))}"
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
                joinerSendingPublicKey = ByteArray(32),
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
                joinerSendingPublicKey = ByteArray(32),
                joinerDisplayLabel = "x",
                groupId = ByteArray(32),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JoinRequestPayload(
                joinerInboxPublicKey = ByteArray(32),
                joinerSendingPublicKey = ByteArray(32),
                joinerDisplayLabel = "x",
                groupId = ByteArray(33),
            )
        }
    }

    // ─── joiner_sending_pub validation (PR A3) ────────────────────

    @Test
    fun constructor_rejectsWrongSizedSendingPub() {
        assertThrows(IllegalArgumentException::class.java) {
            JoinRequestPayload(
                joinerInboxPublicKey = ByteArray(32),
                joinerSendingPublicKey = ByteArray(31),
                joinerDisplayLabel = "x",
                groupId = ByteArray(32),
            )
        }
    }

    @Test
    fun decode_rejectsMissingSendingPub() {
        val text = """
            {
              "joiner_inbox_pub": "${b64(ByteArray(32))}",
              "joiner_display_label": "x",
              "group_id": "${b64(ByteArray(32))}"
            }
        """.trimIndent()
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString(JoinRequestPayload.serializer(), text)
        }
    }

    @Test
    fun decode_rejectsWrongSizedSendingPub() {
        val text = """
            {
              "joiner_inbox_pub": "${b64(ByteArray(32))}",
              "joiner_sending_pub": "${b64(ByteArray(16))}",
              "joiner_display_label": "x",
              "group_id": "${b64(ByteArray(32))}"
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(JoinRequestPayload.serializer(), text)
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
