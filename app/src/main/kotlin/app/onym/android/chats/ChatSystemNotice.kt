package app.onym.android.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.onym.android.strings.R

/**
 * Centred, bubble-less row for a membership notice ("Alice joined").
 *
 * Deliberately unlike [ChatBubble]: no accent fill, no name header, no
 * status glyph, no swipe-to-reply. A notice is the app talking, not a
 * person, and giving it a bubble would put words in a member's mouth.
 *
 * The sentence is assembled here rather than in `:chats-core` because
 * this is where string resources are reachable — [ChatSystemEvent]
 * carries only the structured pieces, so re-wording the copy needs no
 * migration and a language change re-reads existing history in the new
 * language.
 *
 * Mirrors `ChatSystemNoticeCell` in onym-ios.
 */
@Composable
fun ChatSystemNotice(
    event: ChatSystemEvent,
    modifier: Modifier = Modifier,
) {
    val text = when (event) {
        is ChatSystemEvent.MemberJoined ->
            stringResource(R.string.chat_system_member_joined, event.alias)
        is ChatSystemEvent.YouJoined ->
            stringResource(R.string.chat_system_you_joined, event.groupName)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("chat_thread.system_notice"),
        )
    }
}
