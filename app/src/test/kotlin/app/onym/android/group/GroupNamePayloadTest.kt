package app.onym.android.group

import app.onym.android.chats.ChatMessagePayload
import app.onym.android.chats.ChatMessageVariant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Wire-format pin for [GroupNamePayload] (admin group rename). Its
 * distinct `name_*` snake_case keys must match iOS `GroupNamePayload`
 * bit-for-bit, and it must not be structurally confused with an avatar
 * or chat-message payload in either direction.
 */
class GroupNamePayloadTest {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val senderBlsHex = "ab".repeat(48)

    @Test
    fun roundTrip_preservesAllFields() {
        val original = GroupNamePayload(
            version = 1,
            groupId = groupId,
            senderBlsHex = senderBlsHex,
            sentAtMillis = 1_700_000_000_000L,
            name = "Maple Garden",
        )
        val text = json.encodeToString(GroupNamePayload.serializer(), original)
        assertTrue("name_version key", text.contains("\"name_version\":"))
        assertTrue("name_group_id key", text.contains("\"name_group_id\":"))
        assertTrue("name_sender_bls_hex key", text.contains("\"name_sender_bls_hex\":"))
        assertTrue("name_sent_at_millis key", text.contains("\"name_sent_at_millis\":"))
        assertTrue("name_value key", text.contains("\"name_value\":"))
        assertEquals(original, json.decodeFromString(GroupNamePayload.serializer(), text))
    }

    @Test
    fun namePayload_doesNotDecodeAsAvatarOrChat() {
        val text = json.encodeToString(
            GroupNamePayload.serializer(),
            GroupNamePayload(1, groupId, senderBlsHex, 1L, "G"),
        )
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupAvatarPayload.serializer(), text)
        }
        assertThrows(Exception::class.java) {
            json.decodeFromString(ChatMessagePayload.serializer(), text)
        }
    }

    @Test
    fun chatMessage_doesNotDecodeAsNamePayload() {
        val chatText = json.encodeToString(
            ChatMessagePayload.serializer(),
            ChatMessagePayload(
                version = 1,
                messageId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                groupId = groupId,
                senderBlsPubkeyHex = senderBlsHex,
                sentAtMillis = 1L,
                variant = ChatMessageVariant.Tyranny(body = "hi"),
            ),
        )
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupNamePayload.serializer(), chatText)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGroupIdOfWrongLength() {
        GroupNamePayload(1, ByteArray(31), senderBlsHex, 1L, "G")
    }
}
