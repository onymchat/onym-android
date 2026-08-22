package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.onym.android.backup.BackupError
import app.onym.android.backup.BackupRestoreSummary
import app.onym.android.backup.LocalFailureReason
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.foundation.AndroidStringProvider
import app.onym.android.foundation.StringProvider
import app.onym.android.strings.R
import kotlinx.coroutines.launch

internal sealed class RestoreScreenState {
    data object Idle : RestoreScreenState()
    data object Running : RestoreScreenState()
    data class Done(val summary: BackupRestoreSummary) : RestoreScreenState()
    /** A failure before any write — gates 1 (verify) and 2 (decode)
     *  never touch the sink, so the device is genuinely untouched. */
    data class Failed(val message: String) : RestoreScreenState()
    /** `BackupError.LocalFailure(RestoreInterrupted)` — gate 3 (write)
     *  failed partway. Writes are idempotent `insertOrUpdate`, so
     *  earlier rows in this attempt were already committed; telling
     *  the user "nothing changed" here would be false. */
    data class PartiallyRestored(val message: String) : RestoreScreenState()
}

/**
 * History restore is NOT identity restore — it derives the archive
 * key from the identity already on the device and writes only through
 * idempotent `insertOrUpdate` paths, so it's safe to reach live, from
 * Settings, at any time (not gated behind onboarding or a fresh
 * device).
 *
 * An empty restore result is presented as an ordinary answer, not an
 * error — a different operator or a different identity has a
 * different holder key and legitimately sees nothing. Rendering that
 * as "your history is gone" would be the wrong read.
 *
 * Mirrors `BackupRestoreView`/`BackupRestoreFlow` in onym-ios
 * OnymBackupUI (PR #281).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    makeRestoreFlow: (suspend () -> BackupRestoreSummary)?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<RestoreScreenState>(RestoreScreenState.Idle) }
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringProvider(context) }
    val genericFailedMessage = stringResource(R.string.backup_restore_generic_failed)
    val interruptedDefaultMessage = stringResource(R.string.backup_restore_interrupted_default)
    val unavailableMessage = stringResource(R.string.backup_restore_unavailable)

    fun startRestore() {
        state = RestoreScreenState.Running
        scope.launch {
            state = try {
                val summary = makeRestoreFlow?.invoke()
                if (summary == null) {
                    RestoreScreenState.Failed(unavailableMessage)
                } else {
                    RestoreScreenState.Done(summary)
                }
            } catch (e: BackupError.LocalFailure) {
                if (e.reason == LocalFailureReason.RestoreInterrupted) {
                    // Gate 3 (write) failed partway — earlier rows in
                    // this attempt are already committed (writes are
                    // idempotent insertOrUpdate).
                    RestoreScreenState.PartiallyRestored(e.message ?: interruptedDefaultMessage)
                } else {
                    RestoreScreenState.Failed(e.message ?: genericFailedMessage)
                }
            } catch (e: Exception) {
                // Gates 1 (verify) and 2 (decode) never write — a
                // failure here genuinely leaves the device untouched.
                RestoreScreenState.Failed(e.message ?: genericFailedMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.backup_restore_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backup.restore.back")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is RestoreScreenState.Idle -> Column(modifier = Modifier.padding(padding)) {
                SettingsFootnote(
                    stringResource(R.string.backup_restore_intro),
                    modifier = Modifier.testTag("backup.restore.intro"),
                )
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.CloudDownload, SettingsTile.Green) },
                        title = stringResource(R.string.backup_settings_restore_row_title),
                        subtitle = stringResource(R.string.backup_restore_row_subtitle),
                        onClick = { confirming = true },
                        showChevron = false,
                        isLast = true,
                        modifier = Modifier.testTag("backup.restore.start"),
                    )
                }
            }
            is RestoreScreenState.Running -> CenteredState(padding = padding) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.backup_restore_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("backup.restore.running"),
                )
            }
            is RestoreScreenState.Done -> CenteredState(padding = padding) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = SettingsTile.Green,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.backup_restore_done_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    restoreSummaryText(current.summary, strings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("backup.restore.summary"),
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDone, modifier = Modifier.testTag("backup.restore.done")) {
                    Text(stringResource(R.string.backup_restore_done))
                }
            }
            is RestoreScreenState.Failed -> CenteredState(padding = padding) {
                FailureHeader()
                Text(
                    stringResource(R.string.backup_restore_untouched),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("backup.restore.failed"),
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDone, modifier = Modifier.testTag("backup.restore.close")) {
                    Text(stringResource(R.string.backup_restore_close))
                }
            }
            is RestoreScreenState.PartiallyRestored -> CenteredState(padding = padding) {
                FailureHeader()
                Text(
                    // Deliberately no "nothing changed" line here —
                    // some rows were already committed, so that claim
                    // would be false.
                    stringResource(R.string.backup_restore_partial, current.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("backup.restore.partial"),
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDone, modifier = Modifier.testTag("backup.restore.close_partial")) {
                    Text(stringResource(R.string.backup_restore_close))
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        startRestore()
                    },
                    modifier = Modifier.testTag("backup.restore.confirm"),
                ) {
                    Text(stringResource(R.string.backup_restore_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CenteredState(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun FailureHeader() {
    Icon(
        Icons.Filled.WarningAmber,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(44.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.backup_restore_failed_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
}

/** Pure — testable with a fake [StringProvider] without composing
 *  anything (see `RecoveryPhraseBackupViewModel`'s use of the same
 *  seam elsewhere in this codebase for the precedent). */
internal fun restoreSummaryText(summary: BackupRestoreSummary, strings: StringProvider): String {
    val totalWritten = summary.groups + summary.messages + summary.invitations + summary.consents + summary.blobs
    if (totalWritten == 0) {
        // Deliberately not framed as a failure or a warning — an
        // empty result under a different operator or identity is
        // exactly what this looks like when nothing is wrong.
        return strings[R.string.backup_restore_nothing_found]
    }
    val parts = mutableListOf<String>()
    parts += strings.get(
        R.string.backup_restore_summary_template,
        strings.getQuantity(R.plurals.backup_restore_chats_count, summary.groups),
        strings.getQuantity(R.plurals.backup_restore_messages_count, summary.messages),
    )
    if (summary.skipped.isNotEmpty()) {
        val skippedText = summary.skipped.entries.joinToString(", ") { (kind, count) -> "$count $kind" }
        parts += strings.get(R.string.backup_restore_skipped, skippedText)
    }
    if (summary.unresolvedBlobs.isNotEmpty()) {
        parts += strings.getQuantity(R.plurals.backup_restore_unresolved_blobs, summary.unresolvedBlobs.size)
    }
    return parts.joinToString(" ")
}
