package app.onym.android.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compose chat-thread screen. Tapping a group in the Chats tab
 * opens this destination; the members list is one tap deeper behind
 * the info action in the top bar.
 *
 * Read-only message scrollback lands in this PR: bubble-rendered
 * messages stream from [ChatThreadViewModel.messages] through a
 * `LazyColumn` keyed by message id, with cold-open scroll-to-bottom
 * and only-when-near-bottom auto-scroll on new arrivals so reading
 * older messages doesn't get hijacked. Input panel + send button
 * stays a placeholder — that's the next PR.
 *
 * Mirrors `ChatThreadView.swift` from onym-ios PR #152. iOS uses a
 * UIKit controller + `UITableViewDiffableDataSource`; Compose's
 * `LazyColumn(items, key = ...)` covers the same data-diff
 * concerns. The "scroll on cold open + only when near bottom"
 * heuristic mirrors the iOS controller's logic in
 * [shouldAutoScrollOnAppend].
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
                    ChatThreadTitle(
                        name = group?.name ?: "Chat",
                        memberCount = group?.memberProfiles?.size ?: 0,
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
                messages = messages,
                padding = padding,
                onSend = viewModel::send,
                onRetry = viewModel::retry,
            )
        }
    }
}

@Composable
private fun ChatThreadBody(
    messages: List<ChatMessage>,
    padding: PaddingValues,
    onSend: (String) -> Unit,
    onRetry: (java.util.UUID) -> Unit,
) {
    val listState = rememberLazyListState()
    // Defensive sort. The repository's contract is ascending by
    // sentAtMillis, but a future caller / test stub regressing
    // the order shouldn't surface as a visibly-misordered chat.
    // Mirrors the iOS PR #152 fixup that added the same guard at
    // the controller boundary.
    val sortedMessages = remember(messages) {
        messages.sortedBy { it.sentAtMillis }
    }

    // Auto-scroll behavior:
    //   - cold open: jump to the latest message (no animation).
    //   - subsequent appends: only scroll if the user was already
    //     near the bottom (otherwise reading older messages would
    //     get hijacked).
    var hasInitialScrolled by remember { mutableStateOf(false) }
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            isNearBottom(
                totalItems = total,
                lastVisibleIndex = lastVisibleIndex,
            )
        }
    }
    LaunchedEffect(sortedMessages.size) {
        if (sortedMessages.isEmpty()) return@LaunchedEffect
        val lastIndex = sortedMessages.lastIndex
        if (!hasInitialScrolled) {
            listState.scrollToItem(lastIndex)
            hasInitialScrolled = true
            return@LaunchedEffect
        }
        if (nearBottom) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()  // slides the input panel above the soft keyboard
            .testTag("chat_thread.body"),
    ) {
        if (sortedMessages.isEmpty()) {
            EmptyThread(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("chat_thread.message_list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // key = { it.id } keeps slot identity stable across
                // recompositions; status flips re-render the bubble
                // body because ChatMessage's equals() compares all
                // fields. The iOS twin needed an explicit
                // diffable-identity widen via reconfigureItems for
                // the same property — Compose gives it for free.
                items(
                    items = sortedMessages,
                    key = { it.id },
                ) { message ->
                    ChatBubble(
                        message = message,
                        onRetry = { onRetry(message.id) },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp)
        // PR A8 wires the Send button end-to-end. The VM's send
        // does the trim guard + optimistic insert + status flip
        // through SendMessageInteractor (PR A4). SendMessageError
        // routes into viewModel.lastSendError, which the UI doesn't
        // surface yet — network / fan-out failures land as
        // MessageStatus.FAILED on the bubble (visual indicator
        // lands in a later PR).
        ChatInputPanel(onSend = onSend)
    }
}

/**
 * Heuristic for "is the user effectively scrolled to the bottom"
 * — true when the last visible item index is within the threshold
 * of the last item, or when the list is empty. Pure function so a
 * unit test exercises the policy without standing up a
 * `LazyListState` (which requires Compose's compositor).
 *
 * Wire-equivalent to the iOS controller's
 * `contentOffset.y + frame.height >= contentSize.height - 100`
 * check — same threshold semantics, expressed in item indices
 * since Compose's LazyListState doesn't expose pixel offsets at
 * the public API.
 */
internal fun isNearBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    nearBottomThreshold: Int = NEAR_BOTTOM_INDEX_THRESHOLD,
): Boolean {
    if (totalItems == 0) return true
    if (lastVisibleIndex < 0) return false
    return lastVisibleIndex >= totalItems - nearBottomThreshold
}

/** Items-from-bottom window that counts as "near the bottom" for
 *  the auto-scroll heuristic. 2 = the user is reading the last or
 *  second-to-last message. */
internal const val NEAR_BOTTOM_INDEX_THRESHOLD = 2

@Composable
private fun EmptyThread(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("chat_thread.empty"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No messages yet. Say hi.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two-line nav title: group name on top, "N members" subtitle
 * below. Subtitle is hidden when [memberCount] is `<= 1` — singleton
 * groups (just the creator) and not-yet-loaded groups would render
 * an awkward "0 members" / "1 member" otherwise. Same hide-gate as
 * iOS PR #156.
 */
@Composable
private fun ChatThreadTitle(
    name: String,
    memberCount: Int,
) {
    val subtitle = memberCountSubtitle(memberCount)
    Column(
        modifier = Modifier.testTag("chat_thread.title"),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chat_thread.title_subtitle"),
            )
        }
    }
}

/**
 * Subtitle string for the chat-thread nav. Pluralized as "N members"
 * because the `<= 1` gate guarantees we never render the singular
 * form. Pure function so the unit test pins the gate + format
 * without standing up Compose.
 *
 * Mirrors `subtitle(forMemberCount:)` from onym-ios PR #156.
 */
internal fun memberCountSubtitle(memberCount: Int): String? = when {
    memberCount <= 1 -> null
    else -> "$memberCount members"
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
