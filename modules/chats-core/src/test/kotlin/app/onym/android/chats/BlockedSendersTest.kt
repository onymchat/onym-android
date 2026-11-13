package app.onym.android.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedSendersTest {

    @Test
    fun blocks_perOwner_caseInsensitively() {
        val blocked = BlockedSenders.inMemory()
        blocked.block("ABCD", ownerIdentityId = "alice")
        assertTrue(blocked.isBlocked("abcd", "alice"))
        assertFalse("another identity on this device didn't block them", blocked.isBlocked("abcd", "bob"))
        assertEquals(listOf(BlockedSenders.Entry("alice", "abcd")), blocked.entries("alice"))
    }

    @Test
    fun unblock_andRemoveOwner_forget() {
        val blocked = BlockedSenders.inMemory()
        val before = blocked.revision.value
        blocked.block("aa", "alice")
        blocked.block("bb", "alice")
        blocked.unblock("AA", "alice")
        assertFalse(blocked.isBlocked("aa", "alice"))
        assertTrue(blocked.isBlocked("bb", "alice"))
        blocked.removeOwner("alice")
        assertFalse(blocked.isBlocked("bb", "alice"))
        assertEquals("every change bumps the revision", before + 4, blocked.revision.value)
    }
}
