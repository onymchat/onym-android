package app.onym.android.chats

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.UUID

/**
 * Wire-format pin for [ChatMessagePayload]. Mirrors
 * `ChatMessagePayloadTests.swift` from onym-ios PR #147 — the byte
 * sequences these tests assert on must round-trip with the iOS
 * encoder/decoder unchanged.
 */
class ChatMessagePayloadTest {

    private val strictJson = Json { encodeDefaults = true }

    // ─── round-trip ───────────────────────────────────────────────

    @Test
    fun roundtrip_ascii_body_preservesAllFields() {
        val original = samplePayload(body = "hello")
        val encoded = strictJson.encodeToString(ChatMessagePayload.serializer(), original)
        val decoded = strictJson.decodeFromString(ChatMessagePayload.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun roundtrip_empty_body_preservesAllFields() {
        val original = samplePayload(body = "")
        val encoded = strictJson.encodeToString(ChatMessagePayload.serializer(), original)
        val decoded = strictJson.decodeFromString(ChatMessagePayload.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals("", (decoded.variant as ChatMessageVariant.Tyranny).body)
    }

    @Test
    fun roundtrip_unicode_body_preservesAllFields() {
        val original = samplePayload(body = "héllo 🌍 ñ 中文")
        val encoded = strictJson.encodeToString(ChatMessagePayload.serializer(), original)
        val decoded = strictJson.decodeFromString(ChatMessagePayload.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals("héllo 🌍 ñ 中文", (decoded.variant as ChatMessageVariant.Tyranny).body)
    }

    // ─── wire-format pin ──────────────────────────────────────────

    @Test
    fun wireFormat_pinsTopLevelKeysAndDiscriminator() {
        val groupIdBytes = ByteArray(32) { 0x11 }
        val original = ChatMessagePayload(
            version = 1,
            messageId = UUID.fromString("00112233-4455-6677-8899-AABBCCDDEEFF"),
            groupId = groupIdBytes,
            senderBlsPubkeyHex = "ab".repeat(48),
            sentAtMillis = 1_700_000_000_000L,
            variant = ChatMessageVariant.Tyranny(body = "hi"),
        )

        val encoded = strictJson.encodeToString(ChatMessagePayload.serializer(), original)
        val root = strictJson.parseToJsonElement(encoded).jsonObject

        // Top-level keys are pinned snake_case (load-bearing for cross-platform).
        assertTrue("must have 'version' key", root.containsKey("version"))
        assertTrue("must have 'message_id' key", root.containsKey("message_id"))
        assertTrue("must have 'group_id' key", root.containsKey("group_id"))
        assertTrue(
            "must have 'sender_bls_pubkey_hex' key",
            root.containsKey("sender_bls_pubkey_hex"),
        )
        assertTrue("must have 'sent_at_millis' key", root.containsKey("sent_at_millis"))
        assertTrue("must have 'variant' key", root.containsKey("variant"))

        assertEquals(1, root["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "UUID must wire as uppercase canonical string (iOS UUID.uuidString)",
            "00112233-4455-6677-8899-AABBCCDDEEFF",
            root["message_id"]!!.jsonPrimitive.content,
        )
        // group_id is base64 of 32 bytes of 0x11.
        val groupIdB64 = root["group_id"]!!.jsonPrimitive.content
        assertArrayEquals(groupIdBytes, Base64.getDecoder().decode(groupIdB64))
        assertEquals("ab".repeat(48), root["sender_bls_pubkey_hex"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_000L, root["sent_at_millis"]!!.jsonPrimitive.content.toLong())

        val variant = root["variant"]!!.jsonObject
        assertEquals(
            "discriminator field is 'kind'",
            "tyranny",
            variant["kind"]!!.jsonPrimitive.content,
        )
        assertEquals("hi", variant["body"]!!.jsonPrimitive.content)
    }

    // ─── decode rejection paths ───────────────────────────────────

    @Test
    fun decode_rejectsUnknownKind() {
        val json = """
            {
              "version": 1,
              "message_id": "00000000-0000-0000-0000-000000000000",
              "group_id": "${b64ZeroBytes(32)}",
              "sender_bls_pubkey_hex": "${"ab".repeat(48)}",
              "sent_at_millis": 0,
              "variant": { "kind": "jellyfish", "body": "x" }
            }
        """.trimIndent()
        try {
            strictJson.decodeFromString(ChatMessagePayload.serializer(), json)
            fail("expected SerializationException for unknown kind")
        } catch (e: SerializationException) {
            assertTrue(
                "message should mention the unknown kind, got: ${e.message}",
                e.message?.contains("jellyfish") == true,
            )
        }
    }

    @Test
    fun decode_rejectsKnownButUnsupportedKind() {
        val json = """
            {
              "version": 1,
              "message_id": "00000000-0000-0000-0000-000000000000",
              "group_id": "${b64ZeroBytes(32)}",
              "sender_bls_pubkey_hex": "${"ab".repeat(48)}",
              "sent_at_millis": 0,
              "variant": { "kind": "oneonone", "body": "x" }
            }
        """.trimIndent()
        try {
            strictJson.decodeFromString(ChatMessagePayload.serializer(), json)
            fail("expected SerializationException for unsupported kind")
        } catch (e: SerializationException) {
            assertTrue(
                "message should mention 'not yet supported', got: ${e.message}",
                e.message?.contains("not yet supported") == true,
            )
        }
    }

    @Test
    fun decode_rejectsMissingBody() {
        val json = """
            {
              "version": 1,
              "message_id": "00000000-0000-0000-0000-000000000000",
              "group_id": "${b64ZeroBytes(32)}",
              "sender_bls_pubkey_hex": "${"ab".repeat(48)}",
              "sent_at_millis": 0,
              "variant": { "kind": "tyranny" }
            }
        """.trimIndent()
        try {
            strictJson.decodeFromString(ChatMessagePayload.serializer(), json)
            fail("expected SerializationException for missing body")
        } catch (_: SerializationException) {
            // ok
        }
    }

    @Test
    fun decode_rejectsMissingKind() {
        val json = """
            {
              "version": 1,
              "message_id": "00000000-0000-0000-0000-000000000000",
              "group_id": "${b64ZeroBytes(32)}",
              "sender_bls_pubkey_hex": "${"ab".repeat(48)}",
              "sent_at_millis": 0,
              "variant": { "body": "x" }
            }
        """.trimIndent()
        try {
            strictJson.decodeFromString(ChatMessagePayload.serializer(), json)
            fail("expected SerializationException for missing kind")
        } catch (_: SerializationException) {
            // ok
        }
    }

    // ─── forward compat ───────────────────────────────────────────

    @Test
    fun decode_ignoresUnknownTopLevelFields_whenPermissive() {
        // Older receivers running against a future schema must still
        // decode v1 messages even if new optional fields appear.
        val lenient = Json { ignoreUnknownKeys = true }
        val json = """
            {
              "version": 1,
              "message_id": "00000000-0000-0000-0000-000000000000",
              "group_id": "${b64ZeroBytes(32)}",
              "sender_bls_pubkey_hex": "${"ab".repeat(48)}",
              "sent_at_millis": 0,
              "variant": { "kind": "tyranny", "body": "x" },
              "future_field": "ignored"
            }
        """.trimIndent()
        val decoded = lenient.decodeFromString(ChatMessagePayload.serializer(), json)
        assertEquals("x", (decoded.variant as ChatMessageVariant.Tyranny).body)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun samplePayload(body: String): ChatMessagePayload = ChatMessagePayload(
        version = 1,
        messageId = UUID.fromString("12345678-1234-1234-1234-123456789ABC"),
        groupId = ByteArray(32) { (it * 7).toByte() },
        senderBlsPubkeyHex = "ab".repeat(48),
        sentAtMillis = 1_700_000_000_000L,
        variant = ChatMessageVariant.Tyranny(body = body),
    )

    private fun b64ZeroBytes(n: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(n))
}
