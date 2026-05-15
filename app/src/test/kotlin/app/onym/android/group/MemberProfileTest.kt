package app.onym.android.group

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
class MemberProfileTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun encode_emitsSnakeCaseAndBase64() {
        val inboxPub = ByteArray(32) { 0xAB.toByte() }
        val profile = MemberProfile(alias = "Alice", inboxPublicKey = inboxPub)

        val text = json.encodeToString(MemberProfile.serializer(), profile)

        assertTrue("alias literal", text.contains("\"alias\":\"Alice\""))
        assertTrue(
            "snake_case key",
            text.contains("\"inbox_public_key\":\"${Base64.getEncoder().encodeToString(inboxPub)}\""),
        )
    }

    @Test
    fun decode_roundTripsRawBytes() {
        val inboxPub = ByteArray(32) { (it * 7 and 0xFF).toByte() }
        val original = MemberProfile(alias = "Bob", inboxPublicKey = inboxPub)

        val text = json.encodeToString(MemberProfile.serializer(), original)
        val decoded = json.decodeFromString(MemberProfile.serializer(), text)

        assertEquals("Bob", decoded.alias)
        assertArrayEquals(inboxPub, decoded.inboxPublicKey)
        assertEquals(original, decoded)
    }

    @Test
    fun emptyAlias_roundTrips() {
        val profile = MemberProfile(alias = "", inboxPublicKey = ByteArray(32))
        val text = json.encodeToString(MemberProfile.serializer(), profile)
        val decoded = json.decodeFromString(MemberProfile.serializer(), text)
        assertEquals("", decoded.alias)
        assertArrayEquals(ByteArray(32), decoded.inboxPublicKey)
    }
}
