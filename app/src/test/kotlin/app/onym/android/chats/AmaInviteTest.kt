package app.onym.android.chats

import app.onym.android.group.IntroCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The AMA invite is a capability, not a URL: when it stops decoding the
 * empty chats screen has nowhere to send a first-time user. The length
 * assertion is the guard against an elided paste — a truncated link
 * decodes to nothing at all.
 */
class AmaInviteTest {

    @Test
    fun theInviteDecodes() {
        val cap = IntroCapability.fromLink(AmaInvite.LINK)
        assertNotNull("the AMA invite must decode", cap)
        assertEquals("Onym AMA", cap!!.groupName)
        assertNotNull(cap.introPublicKey)
        assertNotNull("the group's rules ride along with the invite", cap.rules)
    }

    @Test
    fun theInviteIsCopiedWhole() {
        assertEquals(448, AmaInvite.LINK.length)
    }
}
