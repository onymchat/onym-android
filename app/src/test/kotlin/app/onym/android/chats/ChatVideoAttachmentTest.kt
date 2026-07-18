package app.onym.android.chats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Wire-format coverage for [ChatVideoAttachment] on [ChatMessagePayload].
 * The byte sequences must round-trip with the iOS encoder/decoder
 * unchanged (poster embedded, snake_case keys, additive optionality).
 */
class ChatVideoAttachmentTest {

    private val strictJson = Json { encodeDefaults = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun samplePoster() = ChatImageAttachment(
        sha256 = "cd".repeat(32),
        mimeType = "image/jpeg",
        byteSize = 40_000,
        width = 1280,
        height = 720,
        encKey = ByteArray(32) { 0x11 },
        blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
        server = "https://blossom.onym.app",
    )

    private fun sampleVideo() = ChatVideoAttachment(
        sha256 = "ab".repeat(32),
        mimeType = "video/mp4",
        byteSize = 4_200_000,
        width = 1280,
        height = 720,
        durationSeconds = 12.5,
        encKey = ByteArray(32) { 0x22 },
        poster = samplePoster(),
        server = "https://blossom.onym.app",
    )

    private fun payload(video: ChatVideoAttachment?) = ChatMessagePayload(
        version = 1,
        messageId = UUID.fromString("12345678-1234-1234-1234-123456789ABC"),
        groupId = ByteArray(32) { (it * 7).toByte() },
        senderBlsPubkeyHex = "ab".repeat(48),
        sentAtMillis = 1_700_000_000_000L,
        variant = ChatMessageVariant.Tyranny(body = "watch this"),
        videoAttachment = video,
    )

    @Test
    fun payload_withVideo_roundTrips() {
        val original = payload(sampleVideo())
        val encoded = strictJson.encodeToString(ChatMessagePayload.serializer(), original)
        val decoded = strictJson.decodeFromString(ChatMessagePayload.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(sampleVideo().sha256, decoded.videoAttachment?.sha256)
        assertEquals(12.5, decoded.videoAttachment?.durationSeconds)
        // The poster rides inside the video attachment with its own key.
        assertTrue(decoded.videoAttachment!!.poster.encKey.contentEquals(ByteArray(32) { 0x11 }))

        // snake_case wire keys.
        assertTrue(encoded.contains("\"video_attachment\""))
        assertTrue(encoded.contains("\"duration_seconds\""))
        assertTrue(encoded.contains("\"poster\""))
        assertTrue(encoded.contains("\"enc_key\""))
    }

    @Test
    fun payload_withoutVideo_isNull() {
        val decoded = strictJson.decodeFromString(
            ChatMessagePayload.serializer(),
            strictJson.encodeToString(ChatMessagePayload.serializer(), payload(null)),
        )
        assertNull(decoded.videoAttachment)
    }

    @Test
    fun payload_legacyWithoutVideoKey_decodesToNull() {
        val legacy = """
            {
              "version": 1,
              "message_id": "12345678-1234-1234-1234-123456789ABC",
              "group_id": "${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}",
              "sender_bls_pubkey_hex": "${"ab".repeat(48)}",
              "sent_at_millis": 1700000000000,
              "variant": { "kind": "tyranny", "body": "hi" }
            }
        """.trimIndent()
        val decoded = lenientJson.decodeFromString(ChatMessagePayload.serializer(), legacy)
        assertNull(decoded.videoAttachment)
        assertNull(decoded.attachment)
    }

    @Test
    fun formatVideoDuration_formatsAsMinutesSeconds() {
        assertEquals("0:00", formatVideoDuration(0.0))
        assertEquals("0:09", formatVideoDuration(9.4))
        assertEquals("1:07", formatVideoDuration(67.0))
        assertEquals("10:00", formatVideoDuration(600.0))
        assertEquals("0:00", formatVideoDuration(-3.0))
    }
}
