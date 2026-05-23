package app.onym.android.chats

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
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
                    if (groups.isNotEmpty()) {
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
        if (groups.isEmpty()) {
            EmptyState(padding = padding, onCreateGroup = onCreateGroup)
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                items(groups, key = { it.id }) { group ->
                    ChatsRow(group = group, onClick = { onOpenChat(group.id) })
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
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Forum,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.chats_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.chats_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onCreateGroup,
            modifier = Modifier
                .heightIn(min = 50.dp)
                .widthIn(min = 220.dp)
                .testTag("chats.create_group_empty_cta"),
        ) {
            Text(
                text = stringResource(R.string.chats_create_group),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// ─── Row ────────────────────────────────────────────────────────

@Composable
private fun ChatsRow(
    group: ChatGroup,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chats.row.${group.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Reuse the broken-ring brand mark as the per-chat avatar —
        // same identity the user saw on the Create Group hero. Once
        // group avatars / uploads ship, this becomes the
        // image-or-mark fallback.
        OnymGroupAvatar(size = 44.dp, accent = OnymAccent.Blue.color())

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name.ifEmpty { stringResource(R.string.chats_unnamed_group) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = subtitleFor(group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (group.isPublishedOnChain) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = stringResource(R.string.chats_published_on_chain),
                tint = Color(0xFF34C759),
                modifier = Modifier.size(18.dp),
            )
        }
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
