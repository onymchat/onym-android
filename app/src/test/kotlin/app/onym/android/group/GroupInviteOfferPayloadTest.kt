package app.onym.android.group

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Codec + wire-disambiguation tests for [GroupInviteOfferPayload].
 *
 * Mirrors `GroupInviteOfferPayloadTests.swift` from onym-ios PR #158.
 */
class GroupInviteOfferPayloadTest {

    // Mirror the dispatcher's permissive decode — the disambiguation
    // guarantee has to hold even when unknown keys are ignored.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun makeValid() = GroupInviteOfferPayload(
        introPublicKey = ByteArray(32) { 0x11 },
        groupId = ByteArray(32) { 0x22 },
        groupName = "Maple Garden",
        inviterAlias = "Alice",
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val offer = makeValid()
        val encoded = json.encodeToString(GroupInviteOfferPayload.serializer(), offer)
        val decoded = json.decodeFromString(GroupInviteOfferPayload.serializer(), encoded)
        assertEquals(offer, decoded)
        assertEquals(1, decoded.version)
        assertEquals("Maple Garden", decoded.groupName)
        assertEquals("Alice", decoded.inviterAlias)
    }

    @Test
    fun nilGroupName_roundTrips() {
        val offer = GroupInviteOfferPayload(
            introPublicKey = ByteArray(32) { 0x01 },
            groupId = ByteArray(32) { 0x02 },
            groupName = null,
            inviterAlias = "",
        )
        val decoded = json.decodeFromString(
            GroupInviteOfferPayload.serializer(),
            json.encodeToString(GroupInviteOfferPayload.serializer(), offer),
        )
        assertNull(decoded.groupName)
        assertEquals(offer, decoded)
    }

    @Test
    fun wrongIntroKeyLength_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupInviteOfferPayload(
                introPublicKey = ByteArray(31) { 0x11 },
                groupId = ByteArray(32) { 0x22 },
                groupName = null,
                inviterAlias = "A",
            )
        }
    }

    @Test
    fun wrongGroupIdLength_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupInviteOfferPayload(
                introPublicKey = ByteArray(32) { 0x11 },
                groupId = ByteArray(16) { 0x22 },
                groupName = null,
                inviterAlias = "A",
            )
        }
    }

    /**
     * The dispatcher relies on `inviter_alias` + `intro_pub` being
     * unique to this type. A [JoinRequestPayload] (the other
     * intro-keyed payload) must NOT decode as an offer.
     */
    @Test
    fun joinRequestPayload_doesNotDecodeAsOffer() {
        val join = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerBlsPublicKey = ByteArray(48) { 0x02 },
            joinerLeafHash = ByteArray(32) { 0x03 },
            joinerSendingPublicKey = ByteArray(32) { 0x04 },
            joinerDisplayLabel = "Bob",
            groupId = ByteArray(32) { 0x05 },
        )
        val bytes = json.encodeToString(JoinRequestPayload.serializer(), join)
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupInviteOfferPayload.serializer(), bytes)
        }
    }

    /**
     * Conversely, an encoded offer must NOT decode as the membership
     * grant — a stray `group_id` shared between the two types isn't
     * enough to materialize a group from an offer.
     */
    @Test
    fun offer_doesNotDecodeAsInvitation() {
        val bytes = json.encodeToString(GroupInviteOfferPayload.serializer(), makeValid())
        assertThrows(Exception::class.java) {
            json.decodeFromString(GroupInvitationPayload.serializer(), bytes)
        }
    }

    @Test
    fun introCapability_rebuildsFromOffer() {
        val offer = makeValid()
        val cap = offer.introCapability()
        assertEquals(offer.introPublicKey.toList(), cap.introPublicKey.toList())
        assertEquals(offer.groupId.toList(), cap.groupId.toList())
        assertEquals(offer.groupName, cap.groupName)
    }
}
