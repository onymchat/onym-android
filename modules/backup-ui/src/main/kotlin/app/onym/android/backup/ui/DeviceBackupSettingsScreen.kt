package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.strings.R

/**
 * Settings → Device Backup → one operator.
 *
 * [canRestore] gates the "Restore From Backup" row's presence — true
 * whenever backup is already enrolled, independent of its current
 * status (per PR #281's principle: an empty result on restore is an
 * ordinary answer, not something that should be hidden behind a
 * healthy-status gate).
 *
 * Mirrors `DeviceBackupSettingsView` in onym-ios OnymBackupUI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceBackupSettingsScreen(
    flow: DeviceBackupSettingsFlow,
    canRestore: Boolean,
    onBackUpNow: () -> Unit,
    onRestoreFromBackup: () -> Unit,
    onErase: () -> Unit,
    onBack: () -> Unit,
    operatorName: String,
    modifier: Modifier = Modifier,
) {
    val status by flow.status.collectAsState()
    // Two-step confirm, matching Settings' clear-message-cache
    // pattern — erasing at the operator is at least as consequential
    // (it's remote and, per the spec, not reliably undoable) and
    // deserves the same friction, not a single unconfirmed tap.
    var showEraseConfirm1 by remember { mutableStateOf(false) }
    var showEraseConfirm2 by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(operatorName) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backup.settings.back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item { SettingsSectionLabel(stringResource(R.string.backup_section_status)) }
            item {
                val presentation = statusPresentation(status)
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(presentation.icon, presentation.tint) },
                        title = presentation.title,
                        subtitle = presentation.subtitle,
                        isLast = true,
                        modifier = Modifier.testTag("backup.settings.status"),
                    )
                }
            }
            item {
                SettingsFootnote(
                    when (status) {
                        is DeviceBackupStatus.Stale -> stringResource(R.string.backup_footnote_manual)
                        is DeviceBackupStatus.CheckingEarlierBackup ->
                            stringResource(R.string.backup_footnote_checking)
                        else -> stringResource(R.string.backup_footnote_sealed)
                    },
                )
            }

            item {
                val running = status is DeviceBackupStatus.Running
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.CloudUpload, SettingsTile.Blue) },
                        title = stringResource(R.string.backup_settings_back_up_now),
                        subtitle = stringResource(R.string.backup_back_up_now_operator_subtitle),
                        titleColor = if (running) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        onClick = if (running) null else onBackUpNow,
                        showChevron = false,
                        isLast = true,
                        modifier = Modifier.testTag("backup.settings.back_up_now"),
                    )
                }
            }
            item { SettingsFootnote(stringResource(R.string.backup_footnote_full_upload)) }

            if (canRestore) {
                item {
                    SettingsCard {
                        SettingsRow(
                            leading = { SettingsTileBox(Icons.Filled.CloudDownload, SettingsTile.Green) },
                            title = stringResource(R.string.backup_settings_restore_row_title),
                            subtitle = stringResource(R.string.backup_settings_restore_row_subtitle),
                            onClick = onRestoreFromBackup,
                            isLast = true,
                            modifier = Modifier.testTag("backup.settings.restore_row"),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.DeleteForever, SettingsTile.Red) },
                        title = stringResource(R.string.backup_settings_erase),
                        subtitle = stringResource(R.string.backup_settings_erase_subtitle),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showEraseConfirm1 = true },
                        showChevron = false,
                        isLast = true,
                        modifier = Modifier.testTag("backup.settings.erase"),
                    )
                }
            }
            item { SettingsFootnote(stringResource(R.string.backup_footnote_erase)) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showEraseConfirm1) {
        AlertDialog(
            onDismissRequest = { showEraseConfirm1 = false },
            title = { Text(stringResource(R.string.backup_erase_confirm_1_title)) },
            text = { Text(stringResource(R.string.backup_erase_confirm_1_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEraseConfirm1 = false
                        showEraseConfirm2 = true
                    },
                    modifier = Modifier.testTag("backup.settings.erase_confirm_1"),
                ) {
                    Text(stringResource(R.string.backup_erase_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEraseConfirm1 = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showEraseConfirm2) {
        AlertDialog(
            onDismissRequest = { showEraseConfirm2 = false },
            title = { Text(stringResource(R.string.backup_erase_confirm_2_title)) },
            text = { Text(stringResource(R.string.backup_erase_confirm_2_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEraseConfirm2 = false
                        onErase()
                    },
                    modifier = Modifier.testTag("backup.settings.erase_confirm_2"),
                ) {
                    Text(stringResource(R.string.backup_erase_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEraseConfirm2 = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
