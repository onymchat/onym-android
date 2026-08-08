package app.onym.android.chats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Wire-format + waveform coverage for voice messages
 * ([ChatVoiceAttachment] on [ChatMessagePayload], [ChatVoiceRecorder]).
 * The byte sequences must round-trip with the iOS encoder/decoder
 * unchanged (snake_case keys, additive optionality, flat waveform array).
 */
class ChatVoiceAttachmentTest {

    private val strictJson = Json { encodeDefaults = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun sampleVoice() = ChatVoiceAttachment(
        sha256 = "ab".repeat(32),
        mimeType = "audio/mp4",
        byteSize = 48_000,
        durationSeconds = 7.5,
        encKey = ByteArray(32) { 0x33 },
        waveform = (0 until 40).map { (it * 6) % 256 },
        server = "https://blossom.onym.app",
    )

    private fun payload(voice: ChatVoiceAttachment?) = ChatMessagePayload(
        version = 1,
        messageId = UUID.fromString("12345678-1234-1234-1234-123456789ABC"),
        groupId = ByteArray(32) { (it * 7).toByte() },
        senderBlsPubkeyHex = "ab".repeat(48),
        sentAtMillis = 1_700_000_000_000L,
        variant = ChatMessageVariant.Tyranny(body = ""),
        voiceAttachment = voice,
    )

    @Test
    fun payload_withVoice_roundTrips() {
        val original = payload(sampleVoice())
        val encoded = strictJson.encodeToString(ChatMessagePayload.serializer(), original)
        val decoded = strictJson.decodeFromString(ChatMessagePayload.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(sampleVoice().sha256, decoded.voiceAttachment?.sha256)
        assertEquals(7.5, decoded.voiceAttachment?.durationSeconds)
        assertEquals(40, decoded.voiceAttachment?.waveform?.size)
        assertNull(decoded.attachment)
        assertNull(decoded.videoAttachment)
        assertNull(decoded.attachments)

        // snake_case wire keys.
        assertTrue(encoded.contains("\"voice_attachment\""))
        assertTrue(encoded.contains("\"duration_seconds\""))
        assertTrue(encoded.contains("\"waveform\""))
        assertTrue(encoded.contains("\"enc_key\""))
    }

    @Test
    fun payload_legacyWithoutVoiceKey_decodesToNull() {
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
        assertNull(decoded.voiceAttachment)
    }

    // ─── waveform downsampling ────────────────────────────────────

    @Test
    fun downsample_emptySamples_returnsZeroedBars() {
        val bars = ChatVoiceRecorder.downsample(emptyList(), 40)
        assertEquals(40, bars.size)
        assertTrue(bars.all { it == 0 })
    }

    @Test
    fun downsample_alwaysReturnsRequestedBarCount() {
        assertEquals(40, ChatVoiceRecorder.downsample(listOf(100, 200, 50), 40).size)
        assertEquals(40, ChatVoiceRecorder.downsample((0 until 10_000).toList(), 40).size)
    }

    @Test
    fun downsample_normalizesToLoudestBucket() {
        // A ramp of increasing amplitude: the loudest bucket hits 255 and
        // the bars are non-decreasing.
        val samples = (0 until 4_000).map { it * 8 }
        val bars = ChatVoiceRecorder.downsample(samples, 40)
        assertEquals(40, bars.size)
        assertEquals(255, bars.max())
        for (i in 1 until bars.size) {
            assertTrue(bars[i] >= bars[i - 1])
        }
    }
}
