package app.onym.android.group

import app.onym.android.chain.SepGroupType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The invite gate, exercised as a table: every governance model against
 *  admin and non-admin. The roster screen and the thread's top bar both
 *  read this answer, so a regression here shows up in two places at once.
 */
class GroupInvitePolicyTest {

    @Test
    fun tyrannyAdminMayInvite() {
        assertTrue(canInviteToGroup(SepGroupType.TYRANNY, isAdmin = true))
    }

    @Test
    fun tyrannyNonAdminMayNot() {
        // A non-admin's invite would be approved by nobody: the contract
        // checks the admin's secret on `update_commitment`.
        assertFalse(canInviteToGroup(SepGroupType.TYRANNY, isAdmin = false))
    }

    @Test
    fun governanceModelsWithoutAnAdmitCeremonyNeverInvite() {
        assertFalse(canInviteToGroup(SepGroupType.ANARCHY, isAdmin = true))
        assertFalse(canInviteToGroup(SepGroupType.ONE_ON_ONE, isAdmin = true))
    }

    @Test
    fun unknownGroupMayNotInvite() {
        // The roster resolves the group asynchronously; before it lands
        // the screen must not offer an invite for a group it cannot name.
        assertFalse(canInviteToGroup(null, isAdmin = true))
    }
}
