package app.onym.android.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.group.LocalOnymTokens
import app.onym.android.identity.IdentitiesViewModel
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.inviteUrl

/**
 * The unified Settings "Identity" surface: a horizontally-swipeable
 * carousel of every identity's invite-key QR (alias highlighted). Landing
 * on a page makes that identity active; the last page mints a new identity
 * from an inline name field over a blurred QR; each identity page carries
 * Share / Backup / Delete under its QR. Mirrors iOS `IdentityCarouselCard`.
 *
 * Invite QRs are pure functions of each summary's `inboxPublicKey`, so every
 * page renders synchronously with no active-only constraint. `select()` is
 * idempotent, so the settle-to-activate gesture fires freely. Backup targets
 * the active identity — which is the visible page after settle.
 */
@Composable
internal fun IdentityCarouselCard(
    viewModel: IdentitiesViewModel,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size + 1 })
    var pendingRemoval by remember { mutableStateOf<IdentitySummary?>(null) }
    var pendingRename by remember { mutableStateOf<IdentitySummary?>(null) }
    var addName by remember { mutableStateOf("") }
    val activeName = items.firstOrNull { it.isActive }?.summary?.name.orEmpty()

    // Land on the active identity's page on first composition.
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) {
        if (!didInitialScroll) {
            val idx = items.indexOfFirst { it.isActive }.coerceAtLeast(0)
            pagerState.scrollToPage(idx)
            didInitialScroll = true
        }
    }

    // Settle → set active (skip the trailing add page). settledPage only
    // updates once the swipe comes to rest, so no extra debounce is needed.
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            items.getOrNull(page)?.let { viewModel.select(it.summary.id) }
        }
    }

    // A just-added identity appends to the list — jump onto its page.
    var prevCount by remember { mutableStateOf(items.size) }
    LaunchedEffect(items.size) {
        if (items.size > prevCount) {
            addName = ""
            pagerState.animateScrollToPage(items.size - 1)
        }
        prevCount = items.size
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
                .testTag("identity.carousel"),
        ) { page ->
            if (page < items.size) {
                val row = items[page]
                IdentityPage(
                    summary = row.summary,
                    isActive = row.isActive,
                    onBackup = {
                        viewModel.select(row.summary.id)
                        onBackup()
                    },
                    onDelete = { pendingRemoval = row.summary },
                    onRename = { pendingRename = row.summary },
                )
            } else {
                AddIdentityPage(
                    name = addName,
                    onNameChange = { addName = it },
                    onCreate = { viewModel.add(name = addName) },
                )
            }
        }
        // Test/semantics hook: always reflects the active identity's alias
        // so a UI test can swipe until the desired identity is active.
        Box(
            modifier = Modifier
                .height(0.dp)
                .semantics { contentDescription = activeName }
                .testTag("identity.carousel.active"),
        )
        // Test/semantics hook: the currently-settled page index. Neighbor
        // pages are composed (so a tag can be *found* off-screen), so a test
        // must wait for the target page to actually settle before tapping a
        // control on it — an off-screen tap coordinate never lands.
        Box(
            modifier = Modifier
                .height(0.dp)
                .semantics { contentDescription = pagerState.settledPage.toString() }
                .testTag("identity.carousel.settled"),
        )
    }

    pendingRemoval?.let { summary ->
        RemoveIdentityDialog(
            displayName = summary.name,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                viewModel.remove(summary.id)
                pendingRemoval = null
            },
        )
    }

    pendingRename?.let { summary ->
        RenameIdentityDialog(
            currentName = summary.name,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                viewModel.rename(summary.id, newName)
                pendingRename = null
            },
        )
    }
}

@Composable
private fun IdentityPage(
    summary: IdentitySummary,
    isActive: Boolean,
    onBackup: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val tokens = LocalOnymTokens.current
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .border(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) accent else tokens.hairline,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            OnymQrCode(value = summary.inviteUrl(), size = 190.dp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRename)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("identity.rename.${summary.id.value}"),
        ) {
            Text(
                text = summary.name.ifBlank { "Identity" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isActive) accent else tokens.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename",
                tint = tokens.text3,
                modifier = Modifier.size(15.dp),
            )
        }
        if (isActive) {
            Text(
                text = "ACTIVE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else {
            Text(
                text = "Swipe here to switch",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.text3,
            )
        }
        Text(
            text = "Start a chat by scanning · BLS ${heroHex(summary.blsPublicKey)}",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CarouselAction(
                icon = Icons.Filled.Share,
                label = "Share",
                accent = accent,
                modifier = Modifier
                    .weight(1f)
                    .testTag("identity.share.${summary.id.value}"),
                onClick = { shareInvite(context, summary) },
            )
            CarouselAction(
                icon = Icons.Filled.VpnKey,
                label = "Backup",
                accent = accent,
                modifier = Modifier
                    .weight(1f)
                    .testTag("identity.backup.${summary.id.value}"),
                onClick = onBackup,
            )
            CarouselAction(
                icon = Icons.Filled.Delete,
                label = "Delete",
                accent = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .weight(1f)
                    .testTag("identity.delete.${summary.id.value}"),
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun CarouselAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LocalOnymTokens.current.surface3)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

@Composable
private fun AddIdentityPage(
    name: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val tokens = LocalOnymTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.4f))
                    .border(1.dp, tokens.hairline, RoundedCornerShape(16.dp))
                    .padding(12.dp)
                    .blur(9.dp),
            ) {
                OnymQrCode(value = "onym-add-identity", size = 190.dp)
            }
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "Add identity",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = tokens.text,
        )
        Text(
            text = "A fresh key — separate contacts and chats from your other identities.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.text2,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            placeholder = { Text("Name (optional)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCreate() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("identity.add.name"),
        )
        Button(
            onClick = onCreate,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("identity.add.create"),
        ) {
            Text("Create identity")
        }
    }
}

/**
 * Rename dialog reached by tapping an identity's alias in the carousel. A
 * rename is a local, display-only change — it never touches the keys or the
 * invite link. Prefilled with the current name; blank input is a no-op.
 * Mirrors iOS `RenameIdentitySheet`.
 */
@Composable
private fun RenameIdentityDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    val trimmed = text.trim()
    val canSave = trimmed.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "A name only you see. It doesn't change your keys or your invite link.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    // Cap matches iOS + IdentityDetail (MAX_IDENTITY_NAME_LENGTH).
                    onValueChange = { if (it.length <= 30) text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onConfirm(trimmed) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity.rename.input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = canSave,
                modifier = Modifier.testTag("identity.rename.confirm"),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Fire the system share sheet with the identity's plain invite link. */
private fun shareInvite(context: android.content.Context?, summary: IdentitySummary) {
    val ctx = context ?: return
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, summary.inviteUrl())
        type = "text/plain"
    }
    ctx.startActivity(Intent.createChooser(sendIntent, "Share invite link"))
}
