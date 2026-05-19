package app.onym.android.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Chat-thread bubble. Two visual modes driven by [ChatMessage.direction]:
 *
 *  - [MessageDirection.OUTGOING] — `colorScheme.primary` fill,
 *    `colorScheme.onPrimary` text, trailing-aligned.
 *  - [MessageDirection.INCOMING] — `colorScheme.surfaceVariant`
 *    fill, `colorScheme.onSurfaceVariant` text, leading-aligned.
 *
 * Bubble max width is capped at [maxWidthFraction] of the parent
 * row (default 75%) so a long monologue doesn't reach the opposite
 * edge. The cap is resolved via `BoxWithConstraints` so it tracks
 * the real parent measurement — no hardcoded `Dp` ceiling.
 *
 * Compose's natural recomposition over the [ChatMessage] data class
 * means a status flip (PENDING → SENT / FAILED) re-renders the
 * bubble without any explicit diffable-identity widening — the
 * outer `LazyColumn(items, key = { it.id })` keeps the slot stable;
 * this body reads the latest fields.
 *
 * Status glyphs, timestamps, avatars, emoji rendering are out of
 * scope for the read-only scrollback PR — they land in later
 * polish slices.
 *
 * Mirrors `ChatBubbleCell.swift` from onym-ios PR #152, Compose-
 * idiomatic: no constraint pairs, no cell reuse, no diffable data
 * source — `BoxWithConstraints` + `LazyColumn(items, key = ...)`
 * cover all three concerns.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    maxWidthFraction: Float = 0.75f,
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
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .clip(RoundedCornerShape(BUBBLE_RADIUS))
                    .background(fill)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("chat_thread.bubble.${message.id}"),
            ) {
                Text(
                    text = message.body,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val BUBBLE_RADIUS = 16.dp
