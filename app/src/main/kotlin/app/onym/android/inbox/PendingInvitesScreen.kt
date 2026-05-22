package app.onym.android.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Invitee-side "you've been invited" list — the push counterpart to the
 * deeplink [app.onym.android.group.JoinScreen]. Mirrors
 * [app.onym.android.group.ApproveRequestsScreen]: a modal of cards, each
 * offering Accept (ship a join request) or Dismiss. Accept is the
 * explicit step; the group only appears once the admin approves the
 * resulting request on chain.
 *
 * Mirrors `PendingInvitesView` from onym-ios PR #158.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingInvitesScreen(
    viewModel: PendingInvitesViewModel,
    onClose: () -> Unit,
) {
    LaunchedEffect(viewModel) { viewModel.start() }

    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val inFlight by viewModel.inFlightIds.collectAsStateWithLifecycle()
    val requested by viewModel.requestedIds.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invitations") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("pending_invites.close_button"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            lastError?.let { ErrorBanner(message = it, onDismiss = viewModel::dismissError) }
            if (pending.isEmpty() && verifying.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(pending, key = { it.id }) { invite ->
                        InviteCard(
                            invite = invite,
                            inFlight = inFlight.contains(invite.id),
                            requested = requested.contains(invite.id),
                            onAccept = { viewModel.accept(invite.id) },
                            onDismiss = { viewModel.dismiss(invite.id) },
                        )
                    }
                    items(verifying, key = { it.id }) { entry ->
                        VerifyingCard(entry = entry, onRetry = { viewModel.retry(entry.groupIdHex) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFDECEC))
            .padding(12.dp)
            .testTag("pending_invites.error_banner"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFD0011B))
        Text(text = message, modifier = Modifier.weight(1f), fontSize = 13.sp)
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(24.dp).testTag("pending_invites.error_dismiss"),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().testTag("pending_invites.empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.MailOutline,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("No invitations", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Group invites you receive show up here for you to accept.",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun InviteCard(
    invite: PendingInvite,
    inFlight: Boolean,
    requested: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
            .testTag("pending_invites.card.${invite.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = displayAlias(invite.inviterAlias),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = "invited you to “${invite.groupName ?: "a group"}”",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !inFlight,
                modifier = Modifier
                    .weight(1f)
                    .testTag("pending_invites.dismiss_button.${invite.id}"),
            ) {
                Text("Dismiss")
            }
            Button(
                onClick = onAccept,
                enabled = !inFlight && !requested,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("pending_invites.accept_button.${invite.id}"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (inFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(acceptLabel(inFlight = inFlight, requested = requested))
                }
            }
        }
    }
}

/**
 * A group accepted but not yet verifiable against the current on-chain
 * state — kept out of the chats list. Shows progress while waiting for
 * the admin's current-state reply, and a Retry when the admin couldn't
 * be reached.
 */
@Composable
private fun VerifyingCard(
    entry: PendingGroupVerification,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
            .testTag("pending_invites.verifying.${entry.groupIdHex}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = entry.groupName.ifEmpty { "Group" },
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        when (entry.status) {
            PendingGroupVerification.Status.VERIFYING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "Verifying group state…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PendingGroupVerification.Status.UNREACHABLE -> {
                Text(
                    "Couldn’t verify — the admin is offline. The group stays hidden until it can be verified on chain.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pending_invites.verifying_retry.${entry.groupIdHex}"),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

private fun acceptLabel(inFlight: Boolean, requested: Boolean): String = when {
    requested -> "Requested — awaiting approval"
    inFlight -> "Sending…"
    else -> "Accept"
}

private fun displayAlias(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.isEmpty()) "Someone" else trimmed
}

/**
 * Chats-toolbar entry point: an envelope with a count badge. Always
 * rendered (like the join-requests button) so the surface is
 * discoverable; the badge only shows when there's at least one pending
 * invite. Pure composable — the chats screen owns the modal state.
 */
@Composable
fun PendingInvitesToolbarBadge(pendingCount: Int) {
    if (pendingCount > 0) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFD0011B))
                .testTag("pending_invites.toolbar_badge"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pendingCount.toString(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
