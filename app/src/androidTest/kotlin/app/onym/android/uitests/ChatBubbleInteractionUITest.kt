package app.onym.android.uitests

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import app.onym.android.chats.ChatBubble
import app.onym.android.chats.ChatMessage
import app.onym.android.chats.MessageDirection
import app.onym.android.chats.MessageStatus
import app.onym.android.chain.SepGroupType
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The bubble's gesture coexistence, pinned behaviorally — the PR 225
 * review showed the layered-modifier version silently swallowed
 * retry taps while every existing suite stayed green, so each claim
 * gets its own interaction test:
 *
 *  1. a FAILED text bubble's tap still fires retry (the long-press
 *     detector and the tap share one recognizer);
 *  2. long-press opens Copy — including on a URL-ONLY body, where the
 *     link machinery owns every glyph;
 *  3. TalkBack reaches Copy: the node exposes a semantics OnLongClick
 *     action (a raw pointerInput exposes none).
 */
class ChatBubbleInteractionUITest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun message(body: String, status: MessageStatus) = ChatMessage(
        id = UUID.randomUUID(),
        groupId = "aa".repeat(32),
        ownerIdentityId = "owner",
        senderBlsPubkeyHex = "cc".repeat(48),
        body = body,
        sentAtMillis = 0L,
        direction = MessageDirection.OUTGOING,
        status = status,
        groupType = SepGroupType.TYRANNY,
    )

    private fun failedMessage(body: String) = ChatMessage(
        id = UUID.randomUUID(),
        groupId = "aa".repeat(32),
        ownerIdentityId = "owner",
        senderBlsPubkeyHex = "cc".repeat(48),
        body = body,
        sentAtMillis = 0L,
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.FAILED,
        groupType = SepGroupType.TYRANNY,
    )

    /** Records openUri calls instead of launching a browser — a real
     *  launch backgrounds the test activity and kills the hierarchy
     *  (how the long-press-follows-link bug was caught). */
    private class RecordingUriHandler : androidx.compose.ui.platform.UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened.add(uri)
        }
    }

    private fun show(
        message: ChatMessage,
        onRetry: (() -> Unit)? = null,
        onSwipeReply: (() -> Unit)? = null,
        uriHandler: RecordingUriHandler? = null,
    ) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    ChatBubble(
                        message = message,
                        onRetry = onRetry,
                        onSwipeReply = onSwipeReply,
                    )
                }
                if (uriHandler != null) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalUriHandler provides uriHandler,
                        content = content,
                    )
                } else {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun failedTextBubbleTapStillFiresRetry() {
        val retries = AtomicInteger(0)
        val message = failedMessage("this one failed to send")
        show(message, onRetry = { retries.incrementAndGet() })

        composeRule.onNodeWithTag("chat_thread.bubble.${message.id}", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        assertEquals(1, retries.get())
    }

    @Test
    fun longPressOpensCopyOnPlainText() {
        val message = failedMessage("copy me")
        show(message)

        composeRule.onNodeWithTag("chat_thread.bubble.${message.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag("chat_thread.copy.${message.id}", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    /** The message type users most want to copy: the body is one
     * link, so every glyph belongs to the link span. */
    @Test
    fun longPressOpensCopyOnUrlOnlyBody() {
        val message = failedMessage("https://onym.app/very/important/path")
        show(message)

        composeRule.onNodeWithTag("chat_thread.bubble.${message.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag("chat_thread.copy.${message.id}", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    /** The riskiest coexistence claim (review): every TEXT bubble now
     * installs a tap detector that consumes the down — the row's
     * drag-to-reply must still win a horizontal drag on a normal,
     * non-failed bubble. Dragged well past SWIPE_REPLY_THRESHOLD
     * (56dp) and released. */
    @Test
    fun swipeToReplyStillFiresOnANormalTextBubble() {
        val replies = AtomicInteger(0)
        val sent = message("just a normal sent message", MessageStatus.SENT)
        show(sent, onSwipeReply = { replies.incrementAndGet() })

        composeRule.onNodeWithTag("chat_thread.bubble.${sent.id}", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(-40f, 0f)) }
                up()
            }
        composeRule.waitForIdle()
        assertEquals(1, replies.get())
    }

    /** The headline feature, at the two positions the hit test gets
     * wrong when it conflates cursor offsets with glyph indices: the
     * MIDDLE of a glyph and the RIGHT half of the last glyph (whose
     * nearest cursor is one past it). Both must open; the empty
     * space just OUTSIDE the text must not. */
    @Test
    fun linkTapsOpenAtGlyphMiddleAndRightHalf() {
        val handler = RecordingUriHandler()
        val url = "https://onym.app/very/important/path"
        val sent = message(url, MessageStatus.SENT)
        show(sent, uriHandler = handler)

        val textNode = composeRule
            .onNodeWithTag("chat_thread.body_links.${sent.id}", useUnmergedTree = true)
        // Middle of the text = middle of some glyph.
        textNode.performTouchInput { click(center) }
        // Right half of the LAST glyph: just inside the trailing edge.
        textNode.performTouchInput {
            click(androidx.compose.ui.geometry.Offset(right - 2f, centerY))
        }
        composeRule.waitForIdle()
        assertEquals(listOf(url, url), handler.opened)
    }

    /** A tap on the bubble padding beside the text falls through to
     * the bubble action, never a clamped link hit. */
    @Test
    fun tapBesideTheLinkDoesNotOpenIt() {
        val handler = RecordingUriHandler()
        val retries = AtomicInteger(0)
        val failed = failedMessage("https://onym.app/x")
        show(failed, onRetry = { retries.incrementAndGet() }, uriHandler = handler)

        // The bubble is wider than the text (padding): tap inside the
        // bubble but right of the text's trailing edge.
        composeRule.onNodeWithTag("chat_thread.bubble.${failed.id}", useUnmergedTree = true)
            .performTouchInput {
                click(androidx.compose.ui.geometry.Offset(right - 2f, centerY))
            }
        composeRule.waitForIdle()
        assertEquals(0, handler.opened.size)
        assertEquals(1, retries.get())
    }

    @Test
    fun copyIsReachableThroughSemanticsLongClick() {
        val message = failedMessage("accessible copy")
        show(message)

        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick).and(
                hasTestTag("chat_thread.bubble.${message.id}"),
            ),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("chat_thread.copy.${message.id}", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
