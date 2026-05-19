package app.onym.android.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-policy tests for the chat-thread nav-subtitle string. Pins
 * the `<= 1` hide-gate + the "N members" format the composable
 * surfaces below the group name.
 *
 * Mirrors `ChatThreadViewControllerTests`' memberCount subtitle
 * assertions from onym-ios PR #156.
 */
class ChatThreadTitleTest {

    @Test
    fun memberCountSubtitle_zero_isNull() {
        // Pre-load / not-yet-resolved group renders an awkward
        // "0 members" subtitle otherwise. Hide it.
        assertNull(memberCountSubtitle(0))
    }

    @Test
    fun memberCountSubtitle_one_isNull() {
        // Singleton group (just the creator) doesn't need the count.
        assertNull(memberCountSubtitle(1))
    }

    @Test
    fun memberCountSubtitle_two_isPluralized() {
        assertEquals("2 members", memberCountSubtitle(2))
    }

    @Test
    fun memberCountSubtitle_manyMembers_isPluralized() {
        assertEquals("100 members", memberCountSubtitle(100))
    }
}
