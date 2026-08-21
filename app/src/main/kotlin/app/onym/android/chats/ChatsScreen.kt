package app.onym.android.chats

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
import app.onym.android.strings.R
import app.onym.android.chain.SepGroupType
import app.onym.android.group.ApproveRequestsViewModel
import app.onym.android.group.ChatGroup
import app.onym.android.design.OnymAccent
import app.onym.android.design.OnymGroupAvatar
import app.onym.android.inbox.PendingChatsViewModel

/**
 * Chats tab — root list of groups the user has created. PR-C only
 * supports Tyranny groups; this list is whatever
 * [app.onym.android.group.GroupRepository.snapshots] emits. Tapping
 * a row is a no-op for now (chat screen is a future slice). The
 * empty-state CTA + the toolbar plus button are the only entry
 * points to Create Group.
 *
 * Mirrors `ChatsView` from onym-ios PR #30.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    onCreateGroup: () -> Unit,
    approveRequestsViewModel: ApproveRequestsViewModel? = null,
    /** Active identity's lowercase BLS pubkey hex, for the admin gate
     *  on the join-request signal. */
    activeBlsPubkeyHex: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    pendingChatsViewModel: PendingChatsViewModel? = null,
    onOpenChat: (groupId: String) -> Unit = {},
    /** Open the thread behind a chat that hasn't opened yet. */
    onOpenPendingChat: (rowId: String) -> Unit = {},
    onScanToJoin: () -> Unit = {},
) {
    val chatItems by viewModel.items.collectAsStateWithLifecycle()
    val pending by (approveRequestsViewModel?.pending?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<app.onym.android.group.JoinRequestApprover.PendingRequest>()) })
    val pendingChats by (pendingChatsViewModel?.rows?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<PendingChatsViewModel.Row>()) })
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    LaunchedEffect(pendingChatsViewModel) { pendingChatsViewModel?.start() }

    // The two kinds of row this list holds, merged and ordered together.
    // Merging here rather than inside `ChatsViewModel` keeps each fact
    // with its owner: the chats VM knows about groups and messages, the
    // pending VM knows about waits, and neither has to learn the
    // other's vocabulary to be sorted next to it.
    val rows: List<ChatsListRow> = remember(chatItems, pendingChats) {
        (chatItems.map(ChatsListRow::Chat) + pendingChats.map(ChatsListRow::Pending))
            .sortedByDescending { it.sortKey }
    }
    // `groups`, not `chatItems`: the enriched list is rebuilt behind an
    // await per group, so gating on it showed the "start your first
    // chat" pitch for a frame or two *after* the first group landed,
    // and hid the compose button for the same beat.
    val hasAnyChats = groups.isNotEmpty() || pendingChats.isNotEmpty()

    // Pending join requests per group id, for the chat-list signal.
    //
    // Removing the toolbar badge took away the *only* ambient hint that
    // someone was waiting. A request isn't a ChatMessage, so it moves no
    // `latestPreview`, and the "X joined" notice it eventually becomes
    // is deliberately excluded from `unreadCount` — so without this a
    // founder who never opens that particular thread would see nothing
    // at all. That is the same discoverability failure this change set
    // out to fix, one screen further in.
    //
    // Built once per render rather than per row.
    //
    // Gated on the same `isAdmin` check the thread uses. Requests are
    // already founder-only by construction — sealed to an intro key no
    // one else holds — but the approver's list is device-wide, so on a
    // two-identity device the non-admin's row would otherwise advertise
    // "Someone wants to join" and open a thread with nothing in it.
    val activeBls by (activeBlsPubkeyHex?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(null) })
    val joinRequestCounts: Map<String, Int> = remember(pending, chatItems, activeBls) {
        val adminGroupIds = chatItems
            .filter { it.group.isAdmin(activeBls) }
            .mapTo(HashSet()) { it.group.id }
        pending
            .map { request -> request.groupId.joinToString("") { "%02x".format(it) } }
            .filter { it in adminGroupIds }
            .groupingBy { it }
            .eachCount()
    }

    // The chat awaiting a swipe-to-delete confirmation, if any.
    var pendingDelete by remember { mutableStateOf<ChatListItem?>(null) }
    // The waiting room awaiting the same, for a row that has already
    // asked to join.
    var pendingDismiss by remember { mutableStateOf<PendingChatsViewModel.Row?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chats_title)) },
                actions = {
                    // Scan-to-join is always available — a brand-new
                    // user with no chats still needs a way in, and an
                    // existing member may want to join another group.
                    IconButton(
                        onClick = onScanToJoin,
                        modifier = Modifier.testTag("chats.scan_to_join_toolbar"),
                    ) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.chats_scan_to_join),
                        )
                    }
                    // Join requests used to live behind a badged button
                    // here, opening a modal list. New users never found
                    // it — nothing in the conversation told them someone
                    // was waiting. They now render as a row inside the
                    // group's own thread, surfaced on the list row
                    // itself (see `joinRequestCounts`), so there is no
                    // separate surface to discover.
                    // Invitations used to live behind a badged envelope
                    // here, opening a modal list of offers. There is no
                    // such surface any more: an offer, and a join we
                    // have asked for, are rows in the list below like
                    // any other chat.
                    // Plus button mirrors Mail / Messages — useful
                    // once the user has at least one chat. Hidden in
                    // the empty state because the central CTA already
                    // covers it.
                    if (hasAnyChats) {
                        IconButton(
                            onClick = onCreateGroup,
                            modifier = Modifier.testTag("chats.create_group_toolbar"),
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.chats_create_group),
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        // The pitch only stands in for an empty list. A chat the person
        // is waiting to be let into is not an empty list — it is the
        // first chat, mid-arrival — so a pending row keeps the pitch
        // away.
        if (!hasAnyChats) {
            EmptyState(
                padding = padding,
                onCreateGroup = onCreateGroup,
                onScanToJoin = onScanToJoin,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatsListRow.Chat -> SwipeableChatRow(
                            item = row.item,
                            onClick = { onOpenChat(row.item.group.id) },
                            onRequestDelete = { pendingDelete = row.item },
                            joinRequestCount = joinRequestCounts[row.item.group.id] ?: 0,
                        )
                        is ChatsListRow.Pending -> SwipeablePendingChatRow(
                            row = row.row,
                            onClick = { onOpenPendingChat(row.row.id) },
                            onDismiss = {
                                // An unanswered offer destroys nothing:
                                // it drops a local row and sends the
                                // founder nothing, whose outstanding
                                // intro key simply goes unused. A row
                                // that has *asked* is the only local
                                // evidence of that asking, and there is
                                // no way back to it without the link
                                // again — so that one asks first.
                                if (row.row.state == PendingChatsViewModel.State.Offered) {
                                    pendingChatsViewModel?.dismiss(row.row.id)
                                } else {
                                    pendingDismiss = row.row
                                }
                            },
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }

    pendingDismiss?.let { row ->
        val name = row.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.pending_chat_unnamed)
        AlertDialog(
            onDismissRequest = { pendingDismiss = null },
            title = { Text(stringResource(R.string.pending_chat_stop_waiting_title)) },
            text = { Text(stringResource(R.string.pending_chat_stop_waiting_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingChatsViewModel?.dismiss(row.id)
                        pendingDismiss = null
                    },
                    modifier = Modifier.testTag("chats.pending.dismiss.confirm"),
                ) {
                    Text(stringResource(R.string.pending_chat_stop_waiting_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDismiss = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Swipe-to-delete confirmation. Deleting wipes the chat + all its
    // messages from this device (local-only); the group may still exist
    // on-chain. Mirrors the iOS confirmationDialog.
    pendingDelete?.let { item ->
        val name = item.group.name.ifBlank {
            stringResource(R.string.chats_delete_message_this_chat)
        }.let { raw ->
            if (item.group.name.isBlank()) raw else "“$raw”"
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chats_delete_title)) },
            text = { Text(stringResource(R.string.chats_delete_message, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(item.group.id)
                        pendingDelete = null
                    },
                    modifier = Modifier.testTag("chats.delete.confirm"),
                ) {
                    Text(stringResource(R.string.chats_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * One entry in the chats list: a chat, or one on its way in.
 *
 * Ordered together — a pending chat sorts by when the wait started,
 * exactly as a real chat sorts by its newest message, so a join
 * happening right now lands at the top, which is where the person who
 * just tapped the link will look for it.
 */
private sealed interface ChatsListRow {
    val key: String
    val sortKey: Long

    data class Chat(val item: ChatListItem) : ChatsListRow {
        override val key: String get() = "chat:${item.id}"
        override val sortKey: Long get() = item.sortKey
    }

    data class Pending(val row: PendingChatsViewModel.Row) : ChatsListRow {
        override val key: String get() = "pending:${row.id}"
        override val sortKey: Long get() = row.receivedAt.toEpochMilli()
    }
}

/**
 * A pending row with the same swipe affordance as a chat, minus the
 * confirmation: dropping an offer destroys nothing and sends nothing.
 *
 * A row synthesised from a verification alone has no stored offer under
 * it to drop, so it doesn't swipe at all — a gesture that silently did
 * nothing would be worse than no gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeablePendingChatRow(
    row: PendingChatsViewModel.Row,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!row.isDismissable) {
        PendingChatRow(row = row, onClick = onClick)
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDismiss()
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { ChatRowDeleteBackground() },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = Modifier.testTag("chats.pending.swipe.${row.id}"),
    ) {
        PendingChatRow(row = row, onClick = onClick)
    }
}

/**
 * Chat-list row for a chat the person is waiting to be let into. Reads
 * as a chat, muted: the same avatar and title layout, no unread badge
 * (no messages exist), and a subtitle that says what is being waited on.
 */
@Composable
private fun PendingChatRow(
    row: PendingChatsViewModel.Row,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chats.pending.${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.alpha(0.6f)) {
            OnymGroupAvatar(size = 44.dp, accent = OnymAccent.Blue.color())
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.pending_chat_unnamed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                // One line, and it has to answer "why isn't this a chat
                // yet?" from the list alone — someone scanning past it
                // should not have to open the row to learn that it is
                // stuck rather than merely slow.
                text = pendingSubtitle(row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.testTag("chats.pending.subtitle.${row.id}"),
            )
        }
    }
}

@Composable
private fun pendingSubtitle(row: PendingChatsViewModel.Row): String = when (row.state) {
    PendingChatsViewModel.State.Offered -> {
        val alias = row.inviterAlias.trim()
        if (alias.isEmpty()) {
            stringResource(R.string.pending_chat_invited_you_generic)
        } else {
            stringResource(R.string.pending_chat_invited_you, alias)
        }
    }
    PendingChatsViewModel.State.Waiting ->
        stringResource(R.string.pending_chat_subtitle_waiting)
    PendingChatsViewModel.State.ChainSettling ->
        stringResource(R.string.pending_chat_almost_in)
    is PendingChatsViewModel.State.SendFailed ->
        stringResource(R.string.pending_chat_subtitle_send_failed)
    else -> stringResource(R.string.pending_chat_subtitle_stuck)
}

/**
 * A chat row that reveals a red Delete background on an end-to-start
 * (right-to-left) swipe. The swipe never dismisses the row itself —
 * crossing the threshold fires [onRequestDelete] (which opens a
 * confirmation dialog) and the row snaps back, so the actual deletion is
 * always gated by the dialog. Mirrors the iOS swipe-then-confirm flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableChatRow(
    item: ChatListItem,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
    joinRequestCount: Int = 0,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRequestDelete()
            }
            // Never settle to dismissed — snap back and let the
            // confirmation dialog decide whether to delete.
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { ChatRowDeleteBackground() },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = Modifier.testTag("chats.row.swipe.${item.group.id}"),
    ) {
        ChatsRow(item = item, onClick = onClick, joinRequestCount = joinRequestCount)
    }
}

@Composable
private fun ChatRowDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.chats_delete_confirm),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

// ─── Empty state ────────────────────────────────────────────────

@Composable
private fun EmptyState(
    padding: PaddingValues,
    onCreateGroup: () -> Unit,
    onScanToJoin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        // Lead with the value, not "you have nothing" — turn the empty
        // state into a pitch for starting the first chat.
        Text(
            text = stringResource(R.string.chats_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.chats_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BenefitRow(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.chats_empty_benefit_encrypted_title),
                detail = stringResource(R.string.chats_empty_benefit_encrypted_detail),
            )
            BenefitRow(
                icon = Icons.Filled.VpnKey,
                title = stringResource(R.string.chats_empty_benefit_identity_title),
                detail = stringResource(R.string.chats_empty_benefit_identity_detail),
            )
            BenefitRow(
                icon = Icons.Filled.Hub,
                title = stringResource(R.string.chats_empty_benefit_decentralized_title),
                detail = stringResource(R.string.chats_empty_benefit_decentralized_detail),
            )
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onCreateGroup,
            modifier = Modifier
                .heightIn(min = 50.dp)
                .fillMaxWidth()
                .testTag("chats.create_group_empty_cta"),
        ) {
            Text(
                text = stringResource(R.string.chats_empty_create_cta),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Secondary affordance: a first-time user who was sent an
        // invite QR (and has no chats yet) joins from here.
        TextButton(
            onClick = onScanToJoin,
            modifier = Modifier.testTag("chats.scan_to_join_empty_cta"),
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.chats_scan_to_join),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** One privacy-benefit line in the empty state: accent icon + a bold
 *  title over a muted one-line detail. */
@Composable
private fun BenefitRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Row ────────────────────────────────────────────────────────

@Composable
private fun ChatsRow(
    item: ChatListItem,
    onClick: () -> Unit,
    /** Join requests waiting in this group's thread. Drives the row's
     *  "someone wants in" signal — the replacement for the toolbar
     *  badge this change removed. */
    joinRequestCount: Int = 0,
) {
    val group = item.group
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque background so the swipe-to-delete red trash behind the
            // row only shows in the revealed area, not through the row.
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chats.row.${group.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Group photo when set, else the broken-ring brand mark — same
        // identity the user saw on the Create Group hero.
        ChatsRowAvatar(group = group, size = 44.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name.ifEmpty { stringResource(R.string.chats_unnamed_group) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (joinRequestCount > 0) {
                // Takes the subtitle outright rather than sitting beside
                // the last message: it is the only line in the row that
                // needs an action, and a small badge alone is what
                // nobody noticed on the toolbar.
                Text(
                    text = if (joinRequestCount == 1) {
                        stringResource(R.string.chats_join_request_one)
                    } else {
                        stringResource(R.string.chats_join_request_many, joinRequestCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("chats.row.join_request.${group.id}"),
                )
            } else {
                Text(
                    text = item.latestPreview?.takeIf { it.isNotEmpty() } ?: subtitleFor(group),
                    style = MaterialTheme.typography.bodySmall,
                    // Unread rows read a touch stronger than the muted metadata.
                    color = if (item.unreadCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("chats.row.subtitle.${group.id}"),
                )
            }
        }

        // Both badges when both apply. They mean different things — one
        // is work waiting for the founder, the other is reading waiting
        // for the reader — and dropping the unread count because someone
        // asked to join loses a signal the row was already carrying.
        if (joinRequestCount > 0) {
            JoinRequestBadge(
                count = joinRequestCount,
                modifier = Modifier.testTag("chats.row.join_request_badge.${group.id}"),
            )
        }
        if (item.unreadCount > 0) {
            UnreadBadge(
                count = item.unreadCount,
                modifier = Modifier.testTag("chats.row.unread.${group.id}"),
            )
        } else if (joinRequestCount == 0 && group.isPublishedOnChain) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = stringResource(R.string.chats_published_on_chain),
                tint = Color(0xFF34C759),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Accent-coloured counterpart to [UnreadBadge] for pending join
 * requests. Deliberately not the error colour: this is an invitation to
 * act, not a backlog of unread messages, and a founder should be able to
 * tell the two apart at a glance down the list.
 */
@Composable
private fun JoinRequestBadge(count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chats_join_request_badge_cd, count)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Red pill showing the unread-message count on a chat row (caps at 99+). */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

/**
 * Per-chat avatar: the group's photo ([ChatGroup.avatar], the raw JPEG)
 * decoded + clipped to a circle when set, otherwise the broken-ring
 * brand mark. `remember(group.avatar)` re-decodes only when the bytes
 * change, so an admin's photo update re-renders the row immediately.
 */
@Composable
private fun ChatsRowAvatar(group: ChatGroup, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(group.avatar) {
        group.avatar?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        OnymGroupAvatar(size = size, accent = OnymAccent.Blue.color())
    }
}

@Composable
private fun subtitleFor(group: ChatGroup): String {
    val label = when (group.groupType) {
        SepGroupType.TYRANNY -> "Founder"
        SepGroupType.ANARCHY -> "Anarchy"
        SepGroupType.ONE_ON_ONE -> "1-on-1"
        SepGroupType.DEMOCRACY -> "Democracy"
        SepGroupType.OLIGARCHY -> "Oligarchy"
    }
    val memberCount = group.memberProfiles.size
    val membersLabel = if (memberCount == 0) {
        ""
    } else {
        pluralStringResource(R.plurals.members_count, memberCount, memberCount)
    }
    val now = System.currentTimeMillis()
    val relative = DateUtils.getRelativeTimeSpanString(
        group.createdAtMillis,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    return if (membersLabel.isEmpty()) "$label · $relative"
    else "$label · $membersLabel · $relative"
}
