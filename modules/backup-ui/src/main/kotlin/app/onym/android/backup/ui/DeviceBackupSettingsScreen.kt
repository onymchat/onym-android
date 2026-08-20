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
import androidx.compose.ui.unit.dp

/**
 * Settings → Device Backup. Status text is a pure function of
 * [DeviceBackupStatus] so it's testable without composing anything.
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
                Text("Back Up Now")
            }

            if (canRestore) {
                Button(
                    onClick = onRestoreFromBackup,
                    modifier = Modifier.padding(top = 8.dp).testTag("backup.settings.restore_row"),
                ) {
                    Column {
                        Text("Restore From Backup")
                        Text("Adds messages and chats — nothing is deleted")
                    }
                }
            }

            Button(
                onClick = onErase,
                modifier = Modifier.padding(top = 8.dp).testTag("backup.settings.erase"),
            ) {
                Text("Erase Backup")
            }
        }
    }
}

/** Pure — testable without composing anything. */
fun statusText(status: DeviceBackupStatus): String = when (status) {
    is DeviceBackupStatus.Off -> "Off"
    is DeviceBackupStatus.Idle -> if (status.lastSuccessAt != null) "Backed up" else "On — no backup yet"
    is DeviceBackupStatus.Running -> "Backing up…"
    is DeviceBackupStatus.Stale -> "Backup is out of date"
    is DeviceBackupStatus.PaymentRequired -> "Payment required to continue backing up"
    is DeviceBackupStatus.TermsChanged -> "The operator's terms changed — review and re-accept to continue"
    is DeviceBackupStatus.OperatorChanged -> "Backup operator changed — review Settings"
    is DeviceBackupStatus.CheckingEarlierBackup -> "Checking an earlier backup…"
    is DeviceBackupStatus.Failed -> "Backup failed: ${status.message}"
}
