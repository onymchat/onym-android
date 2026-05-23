package app.onym.android.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy tests for the chat-thread auto-scroll heuristic.
 * Verifies the `isNearBottom` predicate without standing up a
 * Compose `LazyListState` — the production composable wires this
 * same function to `listState.layoutInfo` via a `derivedStateOf`.
 *
 * Mirrors `ChatThreadViewControllerTests`' `isNearBottom`
 * assertions from onym-ios PR #152 — different transport (item
 * index vs pixel offset) but the same three cases: empty, at end,
 * scrolled up.
 */
class ChatThreadAutoScrollTest {

    // ─── iOS-parity cases ────────────────────────────────────────

    @Test
    fun isNearBottom_emptyListIsNearBottom() {
        assertTrue(
            "an empty list counts as 'at the bottom' so cold opens auto-scroll",
            isNearBottom(totalItems = 0, lastVisibleIndex = -1),
        )
    }

    @Test
    fun isNearBottom_lastIndexIsVisible_isNearBottom() {
        // 50 messages, last visible is index 49 — user is reading
        // the latest message.
        assertTrue(isNearBottom(totalItems = 50, lastVisibleIndex = 49))
    }

    @Test
    fun isNearBottom_scrolledUp_isFalse() {
        // 50 messages, last visible is somewhere in the middle.
        assertFalse(isNearBottom(totalItems = 50, lastVisibleIndex = 10))
    }

    // ─── threshold edges ─────────────────────────────────────────

    @Test
    fun isNearBottom_oneItemFromEnd_isNearBottom() {
        // Threshold = 2 → "last index OR second-to-last index"
        // counts as near. lastVisibleIndex = 48 with 50 total →
        // 48 >= 50 - 2 = 48, true.
        assertTrue(isNearBottom(totalItems = 50, lastVisibleIndex = 48))
    }

    @Test
    fun isNearBottom_twoItemsFromEnd_isNotNearBottom() {
        // lastVisibleIndex = 47 with 50 total → 47 < 48, false.
        // User is reading the third-to-last message — far enough
        // away that a new arrival shouldn't hijack their scroll.
        assertFalse(isNearBottom(totalItems = 50, lastVisibleIndex = 47))
    }

    @Test
    fun isNearBottom_noVisibleIndexYet_isFalse() {
        // lastVisibleIndex = -1 means the list has items but the
        // layout pass hasn't measured anything yet. We only auto-
        // scroll on a known-stable measurement, so this is
        // conservative-false.
        assertFalse(isNearBottom(totalItems = 5, lastVisibleIndex = -1))
    }

    // ─── threshold knob ──────────────────────────────────────────

    @Test
    fun isNearBottom_customThreshold() {
        // With a threshold of 5, the "near bottom" window widens:
        // lastVisibleIndex = 44 with 50 total → 44 >= 45, false;
        // lastVisibleIndex = 45 → 45 >= 45, true.
        assertFalse(isNearBottom(totalItems = 50, lastVisibleIndex = 44, nearBottomThreshold = 5))
        assertTrue(isNearBottom(totalItems = 50, lastVisibleIndex = 45, nearBottomThreshold = 5))
    }

    // ─── keyboard-rise re-anchor (#154) ──────────────────────────

    @Test
    fun shouldGlueToBottomOnImeRise_risingAndAnchored_glues() {
        // Keyboard growing and the user was at the bottom before it
        // opened — re-pin the latest message above the input panel.
        assertTrue(
            shouldGlueToBottomOnImeRise(
                rising = true,
                anchoredBeforeIme = true,
                hasMessages = true,
            ),
        )
    }

    @Test
    fun shouldGlueToBottomOnImeRise_falling_doesNotGlue() {
        // Dismissing the keyboard must never drag a scrolled-up user
        // back down — only rising frames re-pin.
        assertFalse(
            shouldGlueToBottomOnImeRise(
                rising = false,
                anchoredBeforeIme = true,
                hasMessages = true,
            ),
        )
    }

    @Test
    fun shouldGlueToBottomOnImeRise_scrolledUpBeforeKeyboard_doesNotGlue() {
        // The user was reading older history when they focused the
        // input — keep their position instead of yanking to bottom.
        assertFalse(
            shouldGlueToBottomOnImeRise(
                rising = true,
                anchoredBeforeIme = false,
                hasMessages = true,
            ),
        )
    }

    @Test
    fun shouldGlueToBottomOnImeRise_emptyThread_doesNotGlue() {
        // No messages → nothing to pin to; scrolling would be a no-op
        // at best and an index error at worst.
        assertFalse(
            shouldGlueToBottomOnImeRise(
                rising = true,
                anchoredBeforeIme = true,
                hasMessages = false,
            ),
        )
    }
}
