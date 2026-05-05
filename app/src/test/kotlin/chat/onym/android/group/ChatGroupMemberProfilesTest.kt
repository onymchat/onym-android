package chat.onym.android.group

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Equality / default-empty contract for [ChatGroup.memberProfiles].
 *
 * Mirrors `ChatGroupMemberProfilesTests.swift` from onym-ios.
 */
class ChatGroupMemberProfilesTest {

    @Test
    fun defaultEmptyMap() {
        val group = makeGroup(memberProfiles = null)
        assertEquals(emptyMap<String, MemberProfile>(), group.memberProfiles)
    }

    @Test
    fun equalityConsidersMemberProfiles() {
        val a = makeGroup(memberProfiles = mapOf("aa" to MemberProfile("X", ByteArray(32))))
        val b = makeGroup(memberProfiles = mapOf("aa" to MemberProfile("Y", ByteArray(32))))
        val c = makeGroup(memberProfiles = mapOf("aa" to MemberProfile("X", ByteArray(32))))
        assertNotEquals(a, b)
        assertEquals(a, c)
        assertEquals(a.hashCode(), c.hashCode())
    }

    private fun makeGroup(memberProfiles: Map<String, MemberProfile>?): ChatGroup {
        val args = mutableMapOf<String, Any?>()
        return ChatGroup(
            id = "00".repeat(32),
            name = "g",
            groupSecret = ByteArray(32),
            createdAtMillis = 0L,
            members = emptyList(),
            memberProfiles = memberProfiles ?: emptyMap(),
            epoch = 0uL,
            salt = ByteArray(32),
            commitment = null,
            tier = SepTier.SMALL,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = null,
            isPublishedOnChain = false,
            ownerIdentityId = "owner",
        )
    }
}
