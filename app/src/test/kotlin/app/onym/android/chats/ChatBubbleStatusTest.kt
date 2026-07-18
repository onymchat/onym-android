package app.onym.android.chats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import app.onym.android.chain.SepGroupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pure-policy tests for the chat-bubble status indicator and retry
 * gate. Verifies the [statusVisualFor] and [canRetry] predicates
 * the composable consults without standing up Compose.
 *
 * Mirrors `ChatBubbleCellTests`' status-glyph + retry-tap assertions
 * from onym-ios PR #155 — different transport (pure functions vs
 * UIImageView inspection / UITapGestureRecognizer simulation) but
 * same policy.
 */
class ChatBubbleStatusTest {

    // ─── statusVisualFor: glyph + tint mapping ────────────────────

    @Test
    fun statusVisualFor_pending_isMutedClock() {
        val visual = statusVisualFor(MessageStatus.PENDING)
        assertNotNull(visual)
        assertEquals(Icons.Outlined.Schedule, visual!!.icon)
        assertEquals(ChatStatusTint.Muted, visual.tint)
    }

    @Test
    fun statusVisualFor_sent_isMutedCheck() {
        val visual = statusVisualFor(MessageStatus.SENT)
        assertNotNull(visual)
        assertEquals(Icons.Filled.Check, visual!!.icon)
        assertEquals(ChatStatusTint.Muted, visual.tint)
    }

    @Test
    fun statusVisualFor_delivered_isMutedDoubleCheck() {
        val visual = statusVisualFor(MessageStatus.DELIVERED)
        assertNotNull(visual)
        assertEquals(Icons.Filled.DoneAll, visual!!.icon)
        assertEquals(ChatStatusTint.Muted, visual.tint)
    }

    @Test
    fun statusVisualFor_read_isAccentDoubleCheck() {
        val visual = statusVisualFor(MessageStatus.READ)
        assertNotNull(visual)
        assertEquals(Icons.Filled.DoneAll, visual!!.icon)
        assertEquals(ChatStatusTint.Accent, visual.tint)
    }

    @Test
    fun statusVisualFor_failed_isErrorTintedBang() {
        val visual = statusVisualFor(MessageStatus.FAILED)
        assertNotNull(visual)
        assertEquals(Icons.Filled.ErrorOutline, visual!!.icon)
        assertEquals(ChatStatusTint.Error, visual.tint)
    }

    @Test
    fun statusVisualFor_received_isNull() {
        // OUTGOING + RECEIVED is the impossible combination — the
        // bubble defensively hides the indicator rather than render
        // a wrong glyph.
        assertNull(statusVisualFor(MessageStatus.RECEIVED))
    }

    // ─── canRetry: outgoing + failed only ─────────────────────────

    @Test
    fun canRetry_failedOutgoing_isTrue() {
        assertTrue(
            canRetry(
                makeMessage(
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.FAILED,
                ),
            ),
        )
    }

    @Test
    fun canRetry_pendingOutgoing_isFalse() {
        // Retrying a pending row would double-deliver if the
        // in-flight send eventually succeeds.
        assertFalse(
            canRetry(
                makeMessage(
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                ),
            ),
        )
    }

    @Test
    fun canRetry_sentOutgoing_isFalse() {
        // Retrying a sent row would double-deliver outright.
        assertFalse(
            canRetry(
                makeMessage(
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENT,
                ),
            ),
        )
    }

    @Test
    fun canRetry_incomingFailed_isFalse() {
        // Incoming rows have no send to retry; the only meaning of
        // "failed incoming" would be a decode failure, which never
        // lands in the message repository.
        assertFalse(
            canRetry(
                makeMessage(
                    direction = MessageDirection.INCOMING,
                    status = MessageStatus.FAILED,
                ),
            ),
        )
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeMessage(
        direction: MessageDirection,
        status: MessageStatus,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = "aa".repeat(32),
        ownerIdentityId = "owner",
        senderBlsPubkeyHex = "cc".repeat(48),
        body = "x",
        sentAtMillis = 0L,
        direction = direction,
        status = status,
        groupType = SepGroupType.TYRANNY,
    )
}
