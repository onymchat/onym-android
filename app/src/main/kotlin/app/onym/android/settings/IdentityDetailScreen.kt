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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.R
import app.onym.android.group.OnymMark
import app.onym.android.identity.IdentitiesViewModel
import app.onym.android.identity.IdentityId
import app.onym.android.identity.inviteUrl

/**
 * Identity Detail — per-identity hero, backup status, set-active,
 * delete. Backup launches the recovery flow for the active identity;
 * if this identity isn't the active one, taps switch to it first
 * (the `RecoveryPhraseBackupViewModel` reads `repository.snapshots`
 * which always points at the active identity).
 *
 * Uses [IdentitiesViewModel] for select/remove intents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityDetailScreen(
    viewModel: IdentitiesViewModel,
    identityId: IdentityId,
    onBack: () -> Unit,
    onBackup: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val row = items.firstOrNull { it.summary.id == identityId }
    val context = LocalContext.current
    var pendingRemoval by remember { mutableStateOf(false) }

    if (row == null) {
        // Identity was removed (or hasn't loaded yet) — show empty
        // scaffold with the back-button.
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.identity_detail_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val displayName = row.summary.name.ifBlank { stringResource(R.string.identity_unnamed) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier
                .fillMaxSize()
                .testTag("identity_detail.list"),
        ) {
            // ─── Hero ─────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(100.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    (if (row.isActive) SettingsTile.Blue else SettingsTile.Gray)
                                        .copy(alpha = 0.14f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            OnymMark(
                                size = 64.dp,
                                color = if (row.isActive) SettingsTile.Blue else SettingsTile.Gray,
                            )
                        }
                    }
                    EditableIdentityName(
                        currentName = displayName,
                        onSave = { newName -> viewModel.rename(identityId, newName) },
                    )
                    Text(
                        text = heroHex(row.summary.blsPublicKey),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ─── INVITE KEY card ─────────────────────────────────
            // Full-size QR for the identity's invite URL with Copy /
            // Share actions underneath. Mirrors `settings.jsx` lines
            // 873–909 in the iOS prototype.
            item { SettingsSectionLabel(stringResource(R.string.identity_detail_invite_section).uppercase()) }
            item {
                val inviteUrl = remember(row.summary) { row.summary.inviteUrl() }
                val shareTextTemplate = stringResource(
                    R.string.identity_detail_invite_share_text,
                    inviteUrl,
                )
                val shareChooserTitle = stringResource(R.string.identity_detail_invite_share_chooser)
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(12.dp),
                        ) {
                            OnymQrCode(
                                value = inviteUrl,
                                size = 220.dp,
                                modifier = Modifier.testTag("identity_detail.invite_qr"),
                            )
                        }
                        Text(
                            text = stringResource(R.string.identity_detail_invite_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = inviteUrl,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    SettingsHairline(insetStart = 16.dp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, "Invite link", inviteUrl)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("identity_detail.invite_copy"),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.identity_detail_invite_copy))
                        }
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(48.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        )
                        TextButton(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareTextTemplate)
                                    type = "text/plain"
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, shareChooserTitle),
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("identity_detail.invite_share"),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.identity_detail_invite_share))
                        }
                    }
                }
            }
            item { SettingsFootnote(stringResource(R.string.identity_detail_invite_footnote)) }

            // ─── BACKUP card ──────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.identity_detail_backup_section)) }
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SettingsTile.Amber),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.identity_detail_backup_status_unknown),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.identity_detail_backup_status_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SettingsHairline(insetStart = 16.dp)
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Key, SettingsTile.Orange)
                        },
                        title = stringResource(R.string.identity_detail_backup_action),
                        subtitle = stringResource(R.string.identity_detail_backup_action_subtitle),
                        onClick = {
                            // Flip active first (recovery VM reads
                            // repository.snapshots — always the active
                            // identity), then launch the flow.
                            if (!row.isActive) {
                                viewModel.select(identityId)
                            }
                            onBackup()
                        },
                        isLast = true,
                    )
                }
            }

            // ─── STATE ─────────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.identity_detail_state_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Check, SettingsTile.Green)
                        },
                        title = stringResource(R.string.identity_detail_set_active),
                        showChevron = !row.isActive,
                        trailing = if (row.isActive) {
                            {
                                Text(
                                    text = stringResource(R.string.identity_detail_active_marker),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else null,
                        onClick = if (row.isActive) null else {
                            { viewModel.select(identityId) }
                        },
                        isLast = true,
                        modifier = Modifier.testTag("identity_detail.set_active"),
                    )
                }
            }

            // ─── ADVANCED ──────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.identity_detail_advanced_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.ContentCopy, SettingsTile.Gray)
                        },
                        title = stringResource(R.string.identity_detail_copy_bls),
                        subtitle = heroHex(row.summary.blsPublicKey),
                        subtitleMono = true,
                        showChevron = false,
                        onClick = {
                            val hex = row.summary.blsPublicKey.joinToString("") {
                                "%02x".format(it.toInt() and 0xFF)
                            }
                            copyToClipboard(context, "BLS public key", hex)
                        },
                    )
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.ContentCopy, SettingsTile.Gray)
                        },
                        title = stringResource(R.string.identity_detail_copy_inbox),
                        subtitle = heroHex(row.summary.inboxPublicKey),
                        subtitleMono = true,
                        showChevron = false,
                        onClick = {
                            val hex = row.summary.inboxPublicKey.joinToString("") {
                                "%02x".format(it.toInt() and 0xFF)
                            }
                            copyToClipboard(context, "Inbox public key", hex)
                        },
                    )
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Delete, SettingsTile.Red)
                        },
                        title = stringResource(R.string.identity_detail_delete),
                        titleColor = MaterialTheme.colorScheme.error,
                        showChevron = false,
                        onClick = { pendingRemoval = true },
                        isLast = true,
                        modifier = Modifier.testTag("identity_detail.delete"),
                    )
                }
            }
            item {
                SettingsFootnote(stringResource(R.string.identity_detail_delete_footnote))
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (pendingRemoval) {
        RemoveIdentityDialog(
            displayName = displayName,
            onDismiss = { pendingRemoval = false },
            onConfirm = {
                viewModel.remove(identityId)
                pendingRemoval = false
                onBack()
            },
        )
    }
}


/**
 * Tap-to-edit identity alias. Renders [currentName] as a clickable
 * pill with a small edit pencil; tap → inline [BasicTextField] with
 * a thin blue border, autofocus, and a 30-char cap. Commits on
 * `Enter` (`ImeAction.Done`) or focus loss; commit-blank is a
 * silent no-op handled in `IdentityRepository.rename`.
 *
 * The draft state is keyed on [currentName] so an external rename
 * (e.g. another window of the same screen) re-seeds the field
 * cleanly on the next recomposition.
 *
 * Mirrors the iOS prototype's hero-name edit affordance
 * (`settings.jsx` lines 840–865).
 */
@Composable
private fun EditableIdentityName(
    currentName: String,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(currentName) { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }
    // Tracks whether the field has ever held focus this editing
    // session. Compose fires `onFocusChanged` once with
    // `isFocused=false` when the field first enters the focus tree
    // — *before* the `LaunchedEffect` below gets a chance to call
    // `requestFocus()`. Without this latch the initial Inactive
    // event would call `commit()` immediately and snap `editing`
    // back to `false`, making the tap look broken (the field would
    // appear for one frame and disappear).
    var hasGainedFocus by remember(editing) { mutableStateOf(false) }

    val commit: () -> Unit = {
        // Repository trims + treats blank as no-op; we still gate
        // here so a blank submit doesn't trip the unchanged-write
        // path on every blur.
        val trimmed = draft.trim()
        if (trimmed.isNotEmpty() && trimmed != currentName) {
            onSave(trimmed)
        } else {
            draft = currentName
        }
        editing = false
    }

    if (editing) {
        // Defer focus until after the field is composed so the
        // keyboard pops on the same frame the field appears.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        BasicTextField(
            value = draft,
            onValueChange = { draft = it.take(MAX_IDENTITY_NAME_LENGTH) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hasGainedFocus = true
                    } else if (hasGainedFocus) {
                        // Real blur — only fires after focus was
                        // genuinely held at least once.
                        commit()
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("identity_detail.name_field"),
        )
    } else {
        // Min 44dp tall tap target (Material's accessible-touch
        // minimum is 48dp; we sit just under because the headline
        // glyph itself is ~32dp tall and pushing further breaks the
        // hero's vertical rhythm). Pre-fix the row was 22dp tall —
        // small enough that tap misses got blamed on the listener
        // being broken.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { editing = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("identity_detail.name_edit"),
        ) {
            Text(
                text = currentName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.identity_detail_rename_action),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Cap on the editable display alias — matches the iOS prototype's
 *  `e.target.value.slice(0, 30)`. The repository accepts any length
 *  but UI rejects further input past the cap. */
private const val MAX_IDENTITY_NAME_LENGTH = 30

@Composable
internal fun RemoveIdentityDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val canConfirm = typed.trim() == displayName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.identity_detail_delete_dialog_title, displayName)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.identity_detail_delete_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.identity_detail_delete_dialog_typename, displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_detail.delete.confirm.input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("identity_detail.delete.confirm"),
            ) {
                Text(stringResource(R.string.identity_detail_delete_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

