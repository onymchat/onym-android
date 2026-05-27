package app.onym.android.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "Replying to {name}" banner shown above the composer while a reply
 * is armed: an accent bar, the title, a one-line snippet of the
 * quoted body, and a cancel button. The [accent] colors the bar +
 * title so the banner matches the bubble's quote.
 *
 * Mirrors `ChatInputPanelView`'s reply banner from onym-ios PR #175.
 */
@Composable
fun ChatReplyBanner(
    name: String,
    snippet: String,
    accent: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
            .testTag("chat_thread.reply_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "Replying to $name",
                color = accent,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_thread.reply_banner.title"),
            )
            Text(
                text = snippet,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_thread.reply_banner.snippet"),
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.testTag("chat_thread.reply_banner.cancel"),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel reply",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
