package app.onym.android.group

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Round-trip vectors for [MemberAnnouncementPayload]. Cross-checks
 * the snake_case JSON shape + base64 byte encoding so the iOS
 * receiver decodes Android-emitted announcements bit-for-bit.
 *
 * Mirrors `MemberAnnouncementPayloadTests.swift` from onym-ios.
 */
@OptIn(ExperimentalSerializationApi::class)
class MemberAnnouncementPayloadTest {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val blsPub = ByteArray(48) { 0xBB.toByte() }
    private val inboxPub = ByteArray(32) { 0xCC.toByte() }
    private val sendingPub = ByteArray(32) { 0xDE.toByte() }

    @Test
    fun decode_v1WithoutCommitment() {
        val text = """
            {
              "version": 1,
              "group_id": "${b64(groupId)}",
              "new_member": {
                "bls_pub": "${b64(blsPub)}",
                "inbox_pub": "${b64(inboxPub)}",
                "alias": "Bob",
                "sending_pub": "${b64(sendingPub)}"
              },
              "admin_alias": "Alice"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(MemberAnnouncementPayload.serializer(), text)

        assertEquals(1, decoded.version)
        assertArrayEquals(groupId, decoded.groupId)
        assertArrayEquals(blsPub, decoded.newMember.blsPub)
        assertArrayEquals(inboxPub, decoded.newMember.inboxPub)
        assertArrayEquals(sendingPub, decoded.newMember.sendingPub)
        assertEquals("Bob", decoded.newMember.alias)
        assertEquals("Alice", decoded.adminAlias)
        assertNull(decoded.commitment)
        assertNull(decoded.epoch)
    }

    @Test
    fun decode_v1WithCommitmentAndEpoch() {
        val commitment = ByteArray(32) { 0xDD.toByte() }
        val text = """
            {
              "version": 1,
              "group_id": "${b64(groupId)}",
              "new_member": {
                "bls_pub": "${b64(blsPub)}",
                "inbox_pub": "${b64(inboxPub)}",
                "alias": "Bob",
                "sending_pub": "${b64(sendingPub)}"
              },
              "admin_alias": "Alice",
              "commitment": "${b64(commitment)}",
              "epoch": 7
            }
        """.trimIndent()

        val decoded = json.decodeFromString(MemberAnnouncementPayload.serializer(), text)
        assertArrayEquals(commitment, decoded.commitment)
        assertEquals(7uL, decoded.epoch)
    }

    @Test
    fun encode_omitsNullOptionalsWhenNotSet() {
        val payload = MemberAnnouncementPayload(
            version = 1,
            groupId = groupId,
            newMember = MemberAnnouncementPayload.AnnouncedMember(
                blsPub = blsPub, inboxPub = inboxPub, alias = "Bob", sendingPub = sendingPub,
            ),
            adminAlias = "Alice",
        )
        val text = json.encodeToString(MemberAnnouncementPayload.serializer(), payload)
        assertTrue("snake_case key", text.contains("\"group_id\":"))
        assertTrue("snake_case bls", text.contains("\"bls_pub\":"))
        assertTrue("snake_case sending_pub", text.contains("\"sending_pub\":"))
        assertTrue("commitment omitted", !text.contains("\"commitment\""))
        assertTrue("epoch omitted", !text.contains("\"epoch\""))
    }

    @Test
    fun roundTrip_preservesAllFields() {
        val commitment = ByteArray(32) { 0xEE.toByte() }
        val original = MemberAnnouncementPayload(
            version = 1,
            groupId = groupId,
            newMember = MemberAnnouncementPayload.AnnouncedMember(
                blsPub = blsPub, inboxPub = inboxPub, alias = "Bob", sendingPub = sendingPub,
            ),
            adminAlias = "Alice",
            commitment = commitment,
            epoch = 42uL,
        )
        val text = json.encodeToString(MemberAnnouncementPayload.serializer(), original)
        val decoded = json.decodeFromString(MemberAnnouncementPayload.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGroupIdOfWrongLength() {
        MemberAnnouncementPayload(
            version = 1,
            groupId = ByteArray(31),
            newMember = MemberAnnouncementPayload.AnnouncedMember(
                blsPub = blsPub, inboxPub = inboxPub, alias = "Bob", sendingPub = sendingPub,
            ),
            adminAlias = "",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlsPubOfWrongLength() {
        MemberAnnouncementPayload.AnnouncedMember(
            blsPub = ByteArray(47), inboxPub = inboxPub, alias = "Bob", sendingPub = sendingPub,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInboxPubOfWrongLength() {
        MemberAnnouncementPayload.AnnouncedMember(
            blsPub = blsPub, inboxPub = ByteArray(33), alias = "Bob", sendingPub = sendingPub,
        )
    }

    // ─── sending_pub validation (PR A3) ───────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSendingPubOfWrongLength() {
        MemberAnnouncementPayload.AnnouncedMember(
            blsPub = blsPub, inboxPub = inboxPub, alias = "Bob", sendingPub = ByteArray(31),
        )
    }

    @Test
    fun decode_rejectsMissingSendingPub() {
        val text = """
            {
              "version": 1,
              "group_id": "${b64(groupId)}",
              "new_member": {
                "bls_pub": "${b64(blsPub)}",
                "inbox_pub": "${b64(inboxPub)}",
                "alias": "Bob"
              },
              "admin_alias": "Alice"
            }
        """.trimIndent()
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString(MemberAnnouncementPayload.serializer(), text)
        }
    }

    @Test
    fun decode_rejectsWrongSizedSendingPub() {
        val text = """
            {
              "version": 1,
              "group_id": "${b64(groupId)}",
              "new_member": {
                "bls_pub": "${b64(blsPub)}",
                "inbox_pub": "${b64(inboxPub)}",
                "alias": "Bob",
                "sending_pub": "${b64(ByteArray(31))}"
              },
              "admin_alias": "Alice"
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(MemberAnnouncementPayload.serializer(), text)
        }
    }

    @Test
    fun ignoresUnknownFields() {
        val text = """
            {
              "version": 1,
              "group_id": "${b64(groupId)}",
              "new_member": {
                "bls_pub": "${b64(blsPub)}",
                "inbox_pub": "${b64(inboxPub)}",
                "alias": "Bob",
                "sending_pub": "${b64(sendingPub)}"
              },
              "admin_alias": "Alice",
              "future_field_we_do_not_know": 99
            }
        """.trimIndent()
        val decoded = json.decodeFromString(MemberAnnouncementPayload.serializer(), text)
        assertEquals("Alice", decoded.adminAlias)
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
