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
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.runtime.collectAsState
import app.onym.android.R
import app.onym.android.chain.SepGroupType
import app.onym.android.group.ApproveRequestsToolbarBadge
import app.onym.android.group.ApproveRequestsViewModel
import app.onym.android.group.ChatGroup
import app.onym.android.group.OnymAccent
import app.onym.android.group.OnymGroupAvatar
import app.onym.android.inbox.PendingInvitesToolbarBadge
import app.onym.android.inbox.PendingInvitesViewModel

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
    onOpenApproveRequests: (() -> Unit)? = null,
    pendingInvitesViewModel: PendingInvitesViewModel? = null,
    onOpenInvitations: (() -> Unit)? = null,
    onOpenChat: (groupId: String) -> Unit = {},
    onScanToJoin: () -> Unit = {},
) {
    val chatItems by viewModel.items.collectAsStateWithLifecycle()
    val pending by (approveRequestsViewModel?.pending?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<app.onym.android.group.JoinRequestApprover.PendingRequest>()) })
    val pendingInvites by (pendingInvitesViewModel?.pending?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<app.onym.android.inbox.PendingInvite>()) })
    val verifyingInvites by (pendingInvitesViewModel?.verifying?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<app.onym.android.inbox.PendingGroupVerification>()) })
    val inviteBadgeCount = pendingInvites.size + verifyingInvites.size

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
                    if (approveRequestsViewModel != null && onOpenApproveRequests != null) {
                        Box(modifier = Modifier.padding(end = 4.dp)) {
                            IconButton(
                                onClick = onOpenApproveRequests,
                                modifier = Modifier.testTag("approve_requests.toolbar_button"),
                            ) {
                                Icon(
                                    Icons.Filled.PersonAdd,
                                    contentDescription = "Join requests",
                                )
                            }
                            // Numeric red-badge overlay anchored top-end
                            // of the icon button. Mirrors the iOS
                            // ZStack(.topTrailing) layout.
                            if (pending.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-4).dp, y = 6.dp),
                                ) {
                                    ApproveRequestsToolbarBadge(pending.size)
                                }
                            }
                        }
                    }
                    // Invitations received by this identity (push offers).
                    // Same always-rendered + badge-on-nonempty treatment as
                    // the join-requests button.
                    if (pendingInvitesViewModel != null && onOpenInvitations != null) {
                        Box(modifier = Modifier.padding(end = 4.dp)) {
                            IconButton(
                                onClick = onOpenInvitations,
                                modifier = Modifier.testTag("pending_invites.toolbar_button"),
                            ) {
                                Icon(
                                    Icons.Filled.MailOutline,
                                    contentDescription = "Invitations",
                                )
                            }
                            if (inviteBadgeCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-4).dp, y = 6.dp),
                                ) {
                                    PendingInvitesToolbarBadge(inviteBadgeCount)
                                }
                            }
                        }
                    }
                    // Plus button mirrors Mail / Messages — useful
                    // once the user has at least one chat. Hidden in
                    // the empty state because the central CTA already
                    // covers it.
                    if (chatItems.isNotEmpty()) {
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
        if (chatItems.isEmpty()) {
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
                items(chatItems, key = { it.id }) { item ->
                    ChatsRow(item = item, onClick = { onOpenChat(item.group.id) })
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
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
) {
    val group = item.group
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

        if (item.unreadCount > 0) {
            UnreadBadge(
                count = item.unreadCount,
                modifier = Modifier.testTag("chats.row.unread.${group.id}"),
            )
        } else if (group.isPublishedOnChain) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = stringResource(R.string.chats_published_on_chain),
                tint = Color(0xFF34C759),
                modifier = Modifier.size(18.dp),
            )
        }
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
    val membersLabel = when (memberCount) {
        0 -> ""
        1 -> "1 member"
        else -> "$memberCount members"
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
