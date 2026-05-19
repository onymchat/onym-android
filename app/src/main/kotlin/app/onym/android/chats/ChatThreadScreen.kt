package app.onym.android.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compose chat-thread screen. Tapping a group in the Chats tab
 * opens this destination; the members list moves one tap deeper
 * behind the info action in the top bar.
 *
 * Skeleton only — the message list and input panel are placeholders
 * that the next PR fills in (custom bubble cells, auto-scroll,
 * keyboard avoidance, send wiring through [ChatThreadViewModel.send]).
 *
 * The data plumbing is already in place via [ChatThreadViewModel]:
 * the screen collects [ChatThreadViewModel.messages] and
 * [ChatThreadViewModel.group] so when the rendering lands later it's
 * a UI-only diff.
 *
 * Mirrors `ChatThreadView.swift` from onym-ios PR #151 — same nav
 * surface (back + group name + info), same placeholder posture.
 * Diverges from iOS in being a single Compose composable rather
 * than a UIViewControllerRepresentable wrapping a UIKit
 * controller — Compose's nav-bar primitives + diffable LazyColumn
 * cover what iOS needed UIKit for, with no SwiftUI/UIKit-bridge tax.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(
    viewModel: ChatThreadViewModel,
    onBack: () -> Unit,
    onShowMembers: () -> Unit,
) {
    val group by viewModel.group.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = group?.name ?: "Chat",
                        modifier = Modifier.testTag("chat_thread.title"),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_thread.back_button"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onShowMembers,
                        modifier = Modifier.testTag("chat_thread.info_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Members",
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (group == null) {
            MissingGroup(padding = padding)
        } else {
            ChatThreadBody(
                messageCount = messages.size,
                padding = padding,
            )
        }
    }
}

/** Empty placeholder body — message rendering + input panel land in
 *  the next PR. Surfaces the message count so the data plumbing is
 *  visibly wired through to the screen during the skeleton slice. */
@Composable
private fun ChatThreadBody(
    messageCount: Int,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("chat_thread.body"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (messageCount == 0) {
                    "No messages yet. Bubbles render in the next PR."
                } else {
                    "$messageCount message${if (messageCount == 1) "" else "s"} — rendering lands in the next PR."
                },
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .testTag("chat_thread.placeholder_text"),
            )
        }
        HorizontalDivider(thickness = 0.5.dp)
        InputPanelPlaceholder()
    }
}

@Composable
private fun InputPanelPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
            .testTag("chat_thread.input_panel_placeholder"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "Message input lands in the next PR",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingGroup(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("chat_thread.missing"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "This chat isn't on this device.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
