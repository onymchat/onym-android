package app.onym.android.uitests

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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

    private fun show(
        message: ChatMessage,
        onRetry: (() -> Unit)? = null,
        onSwipeReply: (() -> Unit)? = null,
    ) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                ChatBubble(
                    message = message,
                    onRetry = onRetry,
                    onSwipeReply = onSwipeReply,
                )
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
