package app.onym.android.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy tests for the chat-input panel's send-enable +
 * trimming logic. Verifies the [trimmedSendBody] predicate without
 * standing up Compose — the production composable calls this same
 * function to derive both the Send button's `enabled` state and
 * the actual body it hands to the `onSend` callback.
 *
 * Mirrors `ChatInputPanelViewTests`' send-enable + whitespace
 * assertions from onym-ios PR #153 (`initialState_sendDisabled`,
 * `setText_*_send*`, `tapSend_whitespace_isNoOp`). The auto-grow
 * cap is handled natively by `OutlinedTextField(maxLines = 3)` on
 * Compose, so there's no Android equivalent of iOS's
 * `intrinsicHeight(forLines: N)` test.
 */
class ChatInputPanelTest {

    // ─── send-enable policy ──────────────────────────────────────

    @Test
    fun trimmedSendBody_emptyString_isNull() {
        assertNull(trimmedSendBody(""))
    }

    @Test
    fun trimmedSendBody_whitespaceOnly_isNull() {
        assertNull(trimmedSendBody("   "))
        assertNull(trimmedSendBody("\t"))
        assertNull(trimmedSendBody("\n  \t  \n"))
    }

    @Test
    fun trimmedSendBody_nonEmptyAfterTrim_returnsTrimmedBody() {
        assertEquals("hi", trimmedSendBody("hi"))
        assertEquals("hi", trimmedSendBody("  hi  "))
        assertEquals("hi world", trimmedSendBody("\thi world\n"))
    }

    @Test
    fun trimmedSendBody_internalWhitespace_isPreserved() {
        // Only leading + trailing whitespace is trimmed; spaces
        // inside the body are user content and ride through.
        assertEquals("hello   world", trimmedSendBody("  hello   world  "))
    }

    @Test
    fun trimmedSendBody_unicodeBody_roundTrips() {
        assertEquals("héllo 🌍", trimmedSendBody("  héllo 🌍  "))
    }

    // ─── enabled flag derivation (same predicate, asserted via
    //     the not-null shape that ChatInputPanel uses) ────────────

    @Test
    fun isSendEnabled_emptyAndWhitespace_isFalse() {
        assertFalse(isSendEnabled(""))
        assertFalse(isSendEnabled("   "))
    }

    @Test
    fun isSendEnabled_nonEmpty_isTrue() {
        assertTrue(isSendEnabled("hi"))
        assertTrue(isSendEnabled("  hi  "))
    }

    /** Sugar over [trimmedSendBody] — the Send button enables iff
     *  the trim leaves a non-empty body. Lives in the test so the
     *  policy assertion reads the same way the composable does. */
    private fun isSendEnabled(text: String): Boolean = trimmedSendBody(text) != null
}
