package app.onym.android.group

import app.onym.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Modal surface listing pending join requests with Approve / Decline
 * actions. Driven by a shared [ApproveRequestsViewModel]. Empty state
 * is the steady state for users with no outstanding invite links.
 *
 * Trust framing: each row shows the joiner's self-asserted alias
 * alongside the inbox-pubkey hex prefix as an out-of-band fingerprint,
 * matching the guidance documented on [JoinRequestPayload]. Inviters
 * who care about provenance can verify the prefix with the joiner over
 * a side channel before approving.
 *
 * Mirrors `ApproveRequestsView.swift` from onym-ios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApproveRequestsScreen(
    viewModel: ApproveRequestsViewModel,
    onClose: () -> Unit,
) {
    LaunchedEffect(viewModel) { viewModel.start() }

    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val lastSuccess by viewModel.lastSuccessMessage.collectAsStateWithLifecycle()
    val inFlight by viewModel.inFlight.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.approve_requests_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("approve_requests.close_button"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.create_group_close))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            lastSuccess?.let { SuccessBanner(it) }
            lastError?.let {
                ErrorBanner(message = it, onDismiss = viewModel::dismissError)
            }
            if (pending.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(pending, key = { it.id }) { request ->
                        val isInFlight = inFlight.contains(request.id)
                        RequestCard(
                            request = request,
                            inFlight = isInFlight,
                            onApprove = { viewModel.approve(request.id) },
                            onDecline = { viewModel.decline(request.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFE6F7EE))
            .padding(12.dp)
            .testTag("approve_requests.success_banner"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF34C759),
        )
        Column {
            Text(stringResource(R.string.approve_approved_on_chain), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val label = message.ifEmpty { stringResource(R.string.approve_default_person) }
            Text(
                stringResource(R.string.approve_member_added_snackbar, label),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            .testTag("approve_requests.error_banner"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = Color(0xFFD0011B),
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(24.dp)
                .testTag("approve_requests.error_dismiss"),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("approve_requests.empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Inbox,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.approve_empty_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.approve_empty_body),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun RequestCard(
    request: JoinRequestApprover.PendingRequest,
    inFlight: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
            .testTag("approve_requests.row.${request.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = displayAlias(request.joinerDisplayLabel),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            val groupLabel = request.groupName ?: stringResource(R.string.approve_unknown_group)
            Text(
                text = stringResource(R.string.approve_wants_to_join, groupLabel),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.approve_default_inbox_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = hexPrefix(request.joinerInboxPublicKey) + "…",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onDecline,
                enabled = request.groupName != null && !inFlight,
                modifier = Modifier
                    .weight(1f)
                    .testTag("approve_requests.decline_button.${request.id}"),
            ) {
                Text(stringResource(R.string.decline))
            }
            Button(
                onClick = onApprove,
                enabled = request.groupName != null && !inFlight,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("approve_requests.approve_button.${request.id}"),
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
                    Text(if (inFlight) "Anchoring on chain…" else "Approve")
                }
            }
        }
        if (inFlight) {
            // The on-chain admit ceremony is multi-second (PLONK
            // proving + relayer roundtrip + Stellar tx confirmation)
            // — surface that explicitly so the admin doesn't think
            // the tap was lost.
            Text(
                text = stringResource(R.string.approve_generating_proof),
                modifier = Modifier.testTag("approve_requests.in_flight_hint.${request.id}"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (request.groupName == null) {
            Text(
                text = stringResource(R.string.approve_group_not_local),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun displayAlias(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.isEmpty()) "(unnamed)" else trimmed
}

private fun hexPrefix(bytes: ByteArray, count: Int = 8): String {
    val take = bytes.take(count)
    val sb = StringBuilder(count * 2)
    for (b in take) sb.append("%02x".format(b.toInt() and 0xFF))
    return sb.toString()
}

/**
 * Toolbar entry-point — a small icon with a numeric badge when there
 * are pending requests. Always shown so the surface is discoverable.
 *
 * Pure composable; the chats screen owns the modal state.
 */
@Composable
fun ApproveRequestsToolbarBadge(pendingCount: Int) {
    if (pendingCount > 0) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFD0011B))
                .testTag("approve_requests.toolbar_badge"),
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
