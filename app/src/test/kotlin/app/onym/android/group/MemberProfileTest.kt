package app.onym.android.group

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Round-trip vectors for [MemberProfile]. Cross-checks the snake_case
 * JSON shape + base64 byte encoding so onym-ios receivers decode
 * Android-emitted profiles bit-for-bit.
 *
 * Mirrors `MemberProfileTests.swift` from onym-ios.
 */
@OptIn(ExperimentalSerializationApi::class)
class MemberProfileTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun encode_emitsSnakeCaseAndBase64() {
        val inboxPub = ByteArray(32) { 0xAB.toByte() }
        val sendingPub = ByteArray(32) { 0xCD.toByte() }
        val profile = MemberProfile(
            alias = "Alice",
            inboxPublicKey = inboxPub,
            sendingPubkey = sendingPub,
        )

        val text = json.encodeToString(MemberProfile.serializer(), profile)

        assertTrue("alias literal", text.contains("\"alias\":\"Alice\""))
        assertTrue(
            "snake_case key",
            text.contains("\"inbox_public_key\":\"${Base64.getEncoder().encodeToString(inboxPub)}\""),
        )
        assertTrue(
            "sending_pubkey snake_case key",
            text.contains("\"sending_pubkey\":\"${Base64.getEncoder().encodeToString(sendingPub)}\""),
        )
    }

    @Test
    fun decode_roundTripsRawBytes() {
        val inboxPub = ByteArray(32) { (it * 7 and 0xFF).toByte() }
        val sendingPub = ByteArray(32) { (it * 13 and 0xFF).toByte() }
        val original = MemberProfile(
            alias = "Bob",
            inboxPublicKey = inboxPub,
            sendingPubkey = sendingPub,
        )

        val text = json.encodeToString(MemberProfile.serializer(), original)
        val decoded = json.decodeFromString(MemberProfile.serializer(), text)

        assertEquals("Bob", decoded.alias)
        assertArrayEquals(inboxPub, decoded.inboxPublicKey)
        assertArrayEquals(sendingPub, decoded.sendingPubkey)
        assertEquals(original, decoded)
    }

    @Test
    fun emptyAlias_roundTrips() {
        val profile = MemberProfile(
            alias = "",
            inboxPublicKey = ByteArray(32),
            sendingPubkey = ByteArray(32),
        )
        val text = json.encodeToString(MemberProfile.serializer(), profile)
        val decoded = json.decodeFromString(MemberProfile.serializer(), text)
        assertEquals("", decoded.alias)
        assertArrayEquals(ByteArray(32), decoded.inboxPublicKey)
        assertArrayEquals(ByteArray(32), decoded.sendingPubkey)
    }

    // ─── length validation (PR A3) ────────────────────────────────

    @Test
    fun constructor_rejectsWrongSizedSendingPubkey() {
        assertThrows(IllegalArgumentException::class.java) {
            MemberProfile(
                alias = "x",
                inboxPublicKey = ByteArray(32),
                sendingPubkey = ByteArray(31),
            )
        }
    }

    @Test
    fun constructor_rejectsWrongSizedInboxPublicKey() {
        // Bonus length check landed alongside the sending_pubkey
        // addition — the pre-PR-A3 type had no validation at all and
        // would silently accept a 31-byte X25519 key.
        assertThrows(IllegalArgumentException::class.java) {
            MemberProfile(
                alias = "x",
                inboxPublicKey = ByteArray(31),
                sendingPubkey = ByteArray(32),
            )
        }
    }

    @Test
    fun decode_rejectsWrongSizedSendingPubkey() {
        val text = """
            {
              "alias": "x",
              "inbox_public_key": "${b64(ByteArray(32))}",
              "sending_pubkey": "${b64(ByteArray(31))}"
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(MemberProfile.serializer(), text)
        }
    }

    @Test
    fun decode_rejectsMissingSendingPubkey() {
        val text = """
            {
              "alias": "x",
              "inbox_public_key": "${b64(ByteArray(32))}"
            }
        """.trimIndent()
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString(MemberProfile.serializer(), text)
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
