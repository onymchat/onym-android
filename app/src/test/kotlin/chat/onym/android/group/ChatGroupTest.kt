package chat.onym.android.group

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin tests for the [ChatGroup] value type — no SDK FFI, no
 * Room. Mirrors `ChatGroupTests.swift` from onym-ios PR #24 plus a
 * Codable round-trip pin (the iOS twin doesn't bother because Swift's
 * derived Codable is well-trodden; on Android the explicit
 * `Base64ByteArraySerializer` annotations are easy to break, so we
 * pin them).
 */
class ChatGroupTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun groupIdBytes_roundtripsThroughLowercaseHex() {
        val raw = ByteArray(32) { 0xAB.toByte() }
        val hex = raw.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val group = makeGroup(id = hex)
        assertArrayEquals(raw, group.groupIdBytes)
        assertEquals(32, group.groupIdBytes.size)
    }

    @Test
    fun groupIdBytes_handlesShortHex() {
        val group = makeGroup(id = "abcd")
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), group.groupIdBytes)
    }

    @Test
    fun groupIdBytes_isLowercaseInsensitive() {
        val group = makeGroup(id = "AbCd")
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), group.groupIdBytes)
    }

    @Test
    fun roundtrip_preservesAllFields() {
        val original = ChatGroup(
            id = "ab".repeat(32),
            name = "test",
            groupSecret = ByteArray(32) { 0x11 },
            createdAtMillis = 1_700_000_000_000L,
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32) { 0x22 },
            commitment = ByteArray(32) { 0x33 },
            tier = SepTier.SMALL,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = "cd".repeat(48),
            isPublishedOnChain = true,
            ownerIdentityId = "test-owner",
        )
        val encoded = json.encodeToString(ChatGroup.serializer(), original)
        val decoded = json.decodeFromString(ChatGroup.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun roundtrip_nullableCommitmentDecodes() {
        val cold = ChatGroup(
            id = "00".repeat(32),
            name = "cold",
            groupSecret = ByteArray(32),
            createdAtMillis = 0L,
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32),
            commitment = null,
            tier = SepTier.SMALL,
            groupType = SepGroupType.ANARCHY,
            adminPubkeyHex = null,
            isPublishedOnChain = false,
            ownerIdentityId = "test-owner",
        )
        val encoded = json.encodeToString(ChatGroup.serializer(), cold)
        val decoded = json.decodeFromString(ChatGroup.serializer(), encoded)
        assertNull(decoded.commitment)
        assertNull(decoded.adminPubkeyHex)
        assertEquals(cold, decoded)
    }

    private fun makeGroup(id: String): ChatGroup = ChatGroup(
        id = id,
        name = "test",
        groupSecret = ByteArray(32),
        createdAtMillis = 0L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
            ownerIdentityId = "test-owner",
    )
}
