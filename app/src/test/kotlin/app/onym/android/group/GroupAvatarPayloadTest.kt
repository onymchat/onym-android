package app.onym.android.group

import app.onym.android.chats.ChatMessagePayload
import app.onym.android.chats.ChatMessageVariant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

/**
 * Wire-format pin for [GroupAvatarPayload]. Cross-checks the distinct
 * `avatar_*` snake_case keys + base64 byte encoding so the iOS receiver
 * decodes Android-emitted avatar messages bit-for-bit, and confirms the
 * payload can't be structurally confused with a chat message in either
 * direction.
 *
 * Mirrors `GroupAvatarPayloadTests.swift` from onym-ios PR #166.
 */
class GroupAvatarPayloadTest {

    /** Mirrors the dispatcher's permissive decode. */
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val senderBlsHex = "ab".repeat(48) // 96 chars

    @Test
    fun roundTrip_withAvatar_preservesAllFields() {
        val avatar = ByteArray(128) { (it * 3).toByte() }
        val original = GroupAvatarPayload(
            version = 1,
            groupId = groupId,
            senderBlsHex = senderBlsHex,
            sentAtMillis = 1_700_000_000_000L,
            avatar = avatar,
        )
        val text = json.encodeToString(GroupAvatarPayload.serializer(), original)

        // Distinct avatar_* keys + base64 avatar.
        assertTrue("avatar_version key", text.contains("\"avatar_version\":"))
        assertTrue("avatar_group_id key", text.contains("\"avatar_group_id\":"))
        assertTrue("avatar_sender_bls_hex key", text.contains("\"avatar_sender_bls_hex\":"))
        assertTrue("avatar_sent_at_millis key", text.contains("\"avatar_sent_at_millis\":"))

        val decoded = json.decodeFromString(GroupAvatarPayload.serializer(), text)
        assertEquals(original, decoded)
        assertArrayEquals(avatar, decoded.avatar)
    }

    @Test
    fun encode_omitsAvatarKeyWhenRemoved() {
        // Absent avatar = removal: the key must drop off the wire so a
        // receiver reads it as "cleared", not "unspecified".
        val removal = GroupAvatarPayload(
            version = 1,
            groupId = groupId,
            senderBlsHex = senderBlsHex,
            sentAtMillis = 42L,
            avatar = null,
        )
        val text = json.encodeToString(GroupAvatarPayload.serializer(), removal)
        assertTrue("avatar key omitted on removal", !text.contains("\"avatar\":"))
    }

    @Test
    fun decode_absentAvatar_isRemoval() {
        val text = """
            {
              "avatar_version": 1,
              "avatar_group_id": "${b64(groupId)}",
              "avatar_sender_bls_hex": "$senderBlsHex",
              "avatar_sent_at_millis": 42
            }
        """.trimIndent()
        val decoded = json.decodeFromString(GroupAvatarPayload.serializer(), text)
        assertNull("absent avatar decodes to null = removal", decoded.avatar)
    }

    @Test
    fun avatarPayload_doesNotDecodeAsChatMessage() {
        val avatarText = json.encodeToString(
            GroupAvatarPayload.serializer(),
            GroupAvatarPayload(
                version = 1,
                groupId = groupId,
                senderBlsHex = senderBlsHex,
                sentAtMillis = 1L,
                avatar = ByteArray(16) { 0x01 },
            ),
        )
        // ChatMessagePayload requires message_id / sender_bls_pubkey_hex /
        // variant — none present here, so decode must fail.
        assertThrows(Exception::class.java) {
            json.decodeFromString(ChatMessagePayload.serializer(), avatarText)
        }
    }

    @Test
    fun chatMessage_doesNotDecodeAsAvatarPayload() {
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
        // GroupAvatarPayload requires the avatar_* keys — absent here.
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupAvatarPayload.serializer(), chatText)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGroupIdOfWrongLength() {
        GroupAvatarPayload(
            version = 1,
            groupId = ByteArray(31),
            senderBlsHex = senderBlsHex,
            sentAtMillis = 1L,
        )
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
