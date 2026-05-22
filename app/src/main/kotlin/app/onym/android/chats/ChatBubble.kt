package app.onym.android.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Chat-thread bubble. Two visual modes driven by [ChatMessage.direction]:
 *
 *  - [MessageDirection.OUTGOING] — `colorScheme.primary` fill,
 *    `colorScheme.onPrimary` text, trailing-aligned. Status glyph
 *    below the trailing edge (clock / check / red bang).
 *  - [MessageDirection.INCOMING] — `colorScheme.surfaceVariant`
 *    fill, `colorScheme.onSurfaceVariant` text, leading-aligned.
 *    No status indicator (the message arriving is proof of
 *    delivery).
 *
 * Bubble max width is capped at [maxWidthFraction] of the parent
 * row (default 75%) so a long monologue doesn't reach the opposite
 * edge.
 *
 * Failed outgoing bubbles register a tap target that fires [onRetry]
 * (when provided). Non-failed bubbles are unclickable — the gate
 * lives inside [canRetry] so a stale tap that arrives after the
 * status flipped to SENT is a silent no-op, not a double-delivery.
 *
 * Compose's natural recomposition over the [ChatMessage] data class
 * means a status flip (PENDING → SENT / FAILED) re-renders the
 * bubble (and swaps the glyph) without any explicit
 * diffable-identity widening — the outer `LazyColumn(items, key =
 * { it.id })` keeps the slot stable; this body reads the latest
 * fields. iOS PR #155 had to call `snapshot.reconfigureItems(...)`
 * to close the equivalent gap; Compose covers it for free.
 *
 * Mirrors `ChatBubbleCell.swift` from onym-ios PR #155.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    maxWidthFraction: Float = 0.75f,
    onRetry: (() -> Unit)? = null,
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val fill: Color = if (isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor: Color = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val retryEnabled = canRetry(message) && onRetry != null

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        val bubbleMaxWidth = maxWidth * maxWidthFraction
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
            ) {
                BubbleBody(
                    body = message.body,
                    fill = fill,
                    textColor = textColor,
                    messageId = message.id,
                    onClick = if (retryEnabled) onRetry else null,
                )
                if (isOutgoing) {
                    val visual = statusVisualFor(message.status)
                    if (visual != null) {
                        StatusIndicator(
                            visual = visual,
                            messageId = message.id,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleBody(
    body: String,
    fill: Color,
    textColor: Color,
    messageId: java.util.UUID,
    onClick: (() -> Unit)?,
) {
    val baseModifier = Modifier
        .clip(RoundedCornerShape(BUBBLE_RADIUS))
        .background(fill)
    val clickableModifier = if (onClick != null) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }
    Box(
        modifier = clickableModifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("chat_thread.bubble.$messageId"),
    ) {
        Text(
            text = body,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusIndicator(
    visual: StatusVisual,
    messageId: java.util.UUID,
) {
    val tint = when (visual.tint) {
        ChatStatusTint.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatStatusTint.Error -> MaterialTheme.colorScheme.error
    }
    Icon(
        imageVector = visual.icon,
        contentDescription = visual.contentDescription,
        tint = tint,
        modifier = Modifier
            .size(STATUS_ICON_SIZE)
            .padding(end = 4.dp)
            .testTag("chat_thread.status.$messageId"),
    )
}

/**
 * Pure-data view of the glyph + tint a given [MessageStatus] should
 * surface on an outgoing bubble. Pure function so a unit test can
 * pin the mapping without standing up Compose.
 *
 * Returns `null` for [MessageStatus.RECEIVED] — that combination
 * (outgoing + received) shouldn't occur in practice, but a
 * defensive `null` means the bubble silently skips the indicator
 * rather than rendering a wrong glyph.
 */
internal fun statusVisualFor(status: MessageStatus): StatusVisual? = when (status) {
    MessageStatus.PENDING -> StatusVisual(
        icon = Icons.Outlined.Schedule,
        tint = ChatStatusTint.Muted,
        contentDescription = "Sending",
    )
    MessageStatus.SENT -> StatusVisual(
        icon = Icons.Filled.Check,
        tint = ChatStatusTint.Muted,
        contentDescription = "Sent",
    )
    MessageStatus.FAILED -> StatusVisual(
        icon = Icons.Filled.ErrorOutline,
        tint = ChatStatusTint.Error,
        contentDescription = "Failed — tap to retry",
    )
    MessageStatus.RECEIVED -> null
}

/**
 * Retry-eligibility predicate. Pure function so the unit test can
 * pin the gate without standing up Compose — the production
 * composable consults this exact policy to decide whether to
 * register a tap target on the bubble.
 *
 * Failed outgoing rows are retryable; everything else is a no-op.
 * The interactor's [SendMessageInteractor.retry] enforces the same
 * gate so a stale tap that arrives after a status flip can't
 * double-deliver.
 */
internal fun canRetry(message: ChatMessage): Boolean =
    message.direction == MessageDirection.OUTGOING &&
        message.status == MessageStatus.FAILED

/** Visual metadata for the status glyph that sits below an
 *  outgoing bubble. */
internal data class StatusVisual(
    val icon: ImageVector,
    val tint: ChatStatusTint,
    val contentDescription: String,
)

internal enum class ChatStatusTint { Muted, Error }

private val BUBBLE_RADIUS = 16.dp
private val STATUS_ICON_SIZE = 14.dp
