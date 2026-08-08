package app.onym.android.chats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Wire-format tests for [ChatReceiptPayload]. Pins the snake_case keys
 * + kind spelling so the receipt stays byte-compatible with the iOS
 * twin (`ChatReceiptPayload.swift`).
 */
class ChatReceiptPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun roundTrips() {
        val original = ChatReceiptPayload(
            version = 1,
            groupId = byteArrayOf(1, 2, 3),
            senderBlsPubkeyHex = "ab".repeat(48),
            kind = ChatReceiptPayload.Kind.READ,
            messageIds = listOf(UUID.randomUUID(), UUID.randomUUID()),
        )
        val encoded = json.encodeToString(ChatReceiptPayload.serializer(), original)
        val decoded = json.decodeFromString(ChatReceiptPayload.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun wireKeysAndKindMatchIos() {
        val encoded = json.encodeToString(
            ChatReceiptPayload.serializer(),
            ChatReceiptPayload(
                version = 1,
                groupId = byteArrayOf(9),
                senderBlsPubkeyHex = "aa".repeat(48),
                kind = ChatReceiptPayload.Kind.DELIVERED,
                messageIds = listOf(UUID.randomUUID()),
            ),
        )
        assertTrue(encoded.contains("\"group_id\""))
        assertTrue(encoded.contains("\"sender_bls_pubkey_hex\""))
        assertTrue(encoded.contains("\"message_ids\""))
        assertTrue(encoded.contains("\"delivered\""))
    }
}
