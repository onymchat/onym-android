package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ChatGroup.isAdmin` — the half of the invite gate that compares keys.
 *  The comparison is against the stored BLS pubkey, NOT `ownerIdentityId`:
 *  the latter is true for every joiner holding their own copy of the
 *  thread, so it would answer "you own this" everywhere.
 */
class ChatGroupAdminTest {

    /** 48-byte BLS pubkey in hex — the shape `CreateGroupInteractor` stores. */
    private val admin = "9f".repeat(48)

    private fun group(
        groupType: SepGroupType = SepGroupType.TYRANNY,
        adminPubkeyHex: String? = admin,
    ): ChatGroup = ChatGroup(
        id = "ab".repeat(32),
        name = "test",
        groupSecret = ByteArray(32),
        createdAtMillis = 0L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = groupType,
        adminPubkeyHex = adminPubkeyHex,
        isPublishedOnChain = false,
        ownerIdentityId = "test-owner",
    )

    @Test
    fun matchingKeyIsAdmin() {
        assertTrue(group().isAdmin(admin))
    }

    @Test
    fun comparisonIgnoresCase() {
        // The BLS key arrives lowercased from creation and in whatever
        // case an invitation envelope carried it; hex case is not a
        // privilege boundary.
        assertTrue(group(adminPubkeyHex = admin.uppercase()).isAdmin(admin))
        assertTrue(group().isAdmin(admin.uppercase()))
    }

    @Test
    fun anotherKeyIsNotAdmin() {
        assertFalse(group().isAdmin("cd".repeat(48)))
    }

    @Test
    fun missingActiveKeyIsNotAdmin() {
        assertFalse(group().isAdmin(null))
    }

    @Test
    fun groupWithoutAdminHasNoAdmin() {
        // Anarchy / OneOnOne stamp no admin at create time; nobody is
        // privileged there, whatever key they hold.
        assertFalse(group(groupType = SepGroupType.ANARCHY, adminPubkeyHex = null).isAdmin(admin))
    }
}
