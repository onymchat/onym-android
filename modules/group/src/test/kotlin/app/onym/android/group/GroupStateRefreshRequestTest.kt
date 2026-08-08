package app.onym.android.group

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Codec + wire-disambiguation tests for [GroupStateRefreshRequest].
 *
 * Mirrors `GroupStateRefreshRequestCodecTests` from onym-ios PR #159.
 */
class GroupStateRefreshRequestTest {

    // Mirror the dispatcher's permissive decode — disambiguation must
    // hold even when unknown keys are ignored.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun makeValid() = GroupStateRefreshRequest(
        groupId = ByteArray(32) { 0x42 },
        requesterInboxPublicKey = ByteArray(32) { 0x11 },
        requesterBlsPublicKey = ByteArray(48) { 0x22 },
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val req = makeValid()
        val decoded = json.decodeFromString(
            GroupStateRefreshRequest.serializer(),
            json.encodeToString(GroupStateRefreshRequest.serializer(), req),
        )
        assertEquals(req, decoded)
        assertEquals(1, decoded.version)
    }

    @Test
    fun wrongGroupIdLength_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupStateRefreshRequest(
                groupId = ByteArray(31) { 0x42 },
                requesterInboxPublicKey = ByteArray(32) { 0x11 },
                requesterBlsPublicKey = ByteArray(48) { 0x22 },
            )
        }
    }

    @Test
    fun wrongBlsLength_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupStateRefreshRequest(
                groupId = ByteArray(32) { 0x42 },
                requesterInboxPublicKey = ByteArray(32) { 0x11 },
                requesterBlsPublicKey = ByteArray(32) { 0x22 },  // BLS must be 48
            )
        }
    }

    /** An invite offer must NOT decode as a refresh request — disjoint
     *  required keys keep the dispatcher's trial-decode unambiguous. */
    @Test
    fun offer_doesNotDecodeAsRefresh() {
        val offer = GroupInviteOfferPayload(
            introPublicKey = ByteArray(32) { 0x11 },
            groupId = ByteArray(32) { 0x42 },
            groupName = "G",
            inviterAlias = "A",
        )
        val bytes = json.encodeToString(GroupInviteOfferPayload.serializer(), offer)
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupStateRefreshRequest.serializer(), bytes)
        }
    }

    /** Conversely a refresh request must NOT decode as an offer or as a
     *  membership-grant invitation. */
    @Test
    fun refresh_doesNotDecodeAsOfferOrInvitation() {
        val bytes = json.encodeToString(GroupStateRefreshRequest.serializer(), makeValid())
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupInviteOfferPayload.serializer(), bytes)
        }
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupInvitationPayload.serializer(), bytes)
        }
    }
}
