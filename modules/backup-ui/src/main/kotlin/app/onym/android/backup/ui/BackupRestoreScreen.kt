package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.backup.BackupError
import app.onym.android.backup.BackupRestoreSummary
import app.onym.android.backup.LocalFailureReason
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
@Composable
fun BackupRestoreScreen(
    makeRestoreFlow: (suspend () -> BackupRestoreSummary)?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<RestoreScreenState>(RestoreScreenState.Idle) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringProvider(context) }
    val genericFailedMessage = stringResource(R.string.backup_restore_generic_failed)
    val interruptedDefaultMessage = stringResource(R.string.backup_restore_interrupted_default)
    val unavailableMessage = stringResource(R.string.backup_restore_unavailable)

    Scaffold(modifier = modifier) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            when (val current = state) {
                is RestoreScreenState.Idle -> {
                    Text(
                        stringResource(R.string.backup_restore_intro),
                        modifier = Modifier.testTag("backup.restore.intro"),
                    )
                    Button(
                        onClick = {
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
                                        // Gate 3 (write) failed partway —
                                        // earlier rows in this attempt are
                                        // already committed (writes are
                                        // idempotent insertOrUpdate).
                                        RestoreScreenState.PartiallyRestored(e.message ?: interruptedDefaultMessage)
                                    } else {
                                        RestoreScreenState.Failed(e.message ?: genericFailedMessage)
                                    }
                                } catch (e: Exception) {
                                    // Gates 1 (verify) and 2 (decode)
                                    // never write — a failure here
                                    // genuinely leaves the device
                                    // untouched.
                                    RestoreScreenState.Failed(e.message ?: genericFailedMessage)
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.start"),
                    ) {
                        Text(stringResource(R.string.backup_settings_restore_row_title))
                    }
                }
                is RestoreScreenState.Running -> {
                    Text(stringResource(R.string.backup_restore_running), modifier = Modifier.testTag("backup.restore.running"))
                }
                is RestoreScreenState.Done -> {
                    Text(restoreSummaryText(current.summary, strings), modifier = Modifier.testTag("backup.restore.summary"))
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.done")) {
                        Text(stringResource(R.string.backup_restore_done))
                    }
                }
                is RestoreScreenState.Failed -> {
                    Text(
                        stringResource(R.string.backup_restore_failed_prefix, current.message),
                        modifier = Modifier.testTag("backup.restore.failed"),
                    )
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.close")) {
                        Text(stringResource(R.string.backup_restore_close))
                    }
                }
                is RestoreScreenState.PartiallyRestored -> {
                    Text(
                        stringResource(R.string.backup_restore_partial, current.message),
                        modifier = Modifier.testTag("backup.restore.partial"),
                    )
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.close_partial")) {
                        Text(stringResource(R.string.backup_restore_close))
                    }
                }
            }
        }
    }
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
