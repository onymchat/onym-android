package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.strings.R

/**
 * Settings → Device Backup.
 *
 * [canRestore] gates the "Restore From Backup" row's presence — true
 * whenever backup is already enrolled, independent of its current
 * status (per PR #281's principle: an empty result on restore is an
 * ordinary answer, not something that should be hidden behind a
 * healthy-status gate).
 *
 * Mirrors `DeviceBackupSettingsView` in onym-ios OnymBackupUI.
 */
@Composable
fun DeviceBackupSettingsScreen(
    flow: DeviceBackupSettingsFlow,
    canRestore: Boolean,
    onBackUpNow: () -> Unit,
    onRestoreFromBackup: () -> Unit,
    onErase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by flow.status.collectAsState()

    Scaffold(modifier = modifier) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(statusText(status), modifier = Modifier.testTag("backup.settings.status"))

            Button(
                onClick = onBackUpNow,
                enabled = status !is DeviceBackupStatus.Running,
                modifier = Modifier.padding(top = 16.dp).testTag("backup.settings.back_up_now"),
            ) {
                Text(stringResource(R.string.backup_settings_back_up_now))
            }

            if (canRestore) {
                Button(
                    onClick = onRestoreFromBackup,
                    modifier = Modifier.padding(top = 8.dp).testTag("backup.settings.restore_row"),
                ) {
                    Column {
                        Text(stringResource(R.string.backup_settings_restore_row_title))
                        Text(stringResource(R.string.backup_settings_restore_row_subtitle))
                    }
                }
            }

            Button(
                onClick = onErase,
                modifier = Modifier.padding(top = 8.dp).testTag("backup.settings.erase"),
            ) {
                Text(stringResource(R.string.backup_settings_erase))
            }
        }
    }
}

/** Localized rendering of [DeviceBackupStatus] — `@Composable` so it
 *  can call `stringResource`. Every call site is already inside
 *  composition (this screen, and `BackupVendorsListScreen`'s row
 *  list). */
@Composable
fun statusText(status: DeviceBackupStatus): String = when (status) {
    is DeviceBackupStatus.Off -> stringResource(R.string.backup_status_off)
    is DeviceBackupStatus.Idle -> if (status.lastSuccessAt != null) {
        stringResource(R.string.backup_status_backed_up)
    } else {
        stringResource(R.string.backup_status_no_backup_yet)
    }
    is DeviceBackupStatus.Running -> stringResource(R.string.backup_status_backing_up)
    is DeviceBackupStatus.Stale -> stringResource(R.string.backup_status_stale)
    is DeviceBackupStatus.PaymentRequired -> stringResource(R.string.backup_status_payment_required)
    is DeviceBackupStatus.TermsChanged -> stringResource(R.string.backup_status_terms_changed)
    is DeviceBackupStatus.OperatorChanged -> stringResource(R.string.backup_status_operator_changed)
    is DeviceBackupStatus.CheckingEarlierBackup -> stringResource(R.string.backup_status_checking_earlier)
    is DeviceBackupStatus.Failed -> stringResource(R.string.backup_status_failed, status.message)
}
