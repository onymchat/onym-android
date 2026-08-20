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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.onym.android.backup.BackupError
import app.onym.android.backup.BackupRestoreSummary
import app.onym.android.backup.LocalFailureReason
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

    Scaffold(modifier = modifier) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            when (val current = state) {
                is RestoreScreenState.Idle -> {
                    Text(
                        "Adds messages and chats from your most recent backup — nothing on this device is deleted.",
                        modifier = Modifier.testTag("backup.restore.intro"),
                    )
                    Button(
                        onClick = {
                            state = RestoreScreenState.Running
                            scope.launch {
                                state = try {
                                    val summary = makeRestoreFlow?.invoke()
                                    if (summary == null) {
                                        RestoreScreenState.Failed("Restore is not available right now.")
                                    } else {
                                        RestoreScreenState.Done(summary)
                                    }
                                } catch (e: BackupError.LocalFailure) {
                                    if (e.reason == LocalFailureReason.RestoreInterrupted) {
                                        // Gate 3 (write) failed partway —
                                        // earlier rows in this attempt are
                                        // already committed (writes are
                                        // idempotent insertOrUpdate).
                                        RestoreScreenState.PartiallyRestored(
                                            e.message ?: "Restore was interrupted partway through.",
                                        )
                                    } else {
                                        RestoreScreenState.Failed(
                                            e.message ?: "Restore failed. Nothing on this device was changed.",
                                        )
                                    }
                                } catch (e: Exception) {
                                    // Gates 1 (verify) and 2 (decode)
                                    // never write — a failure here
                                    // genuinely leaves the device
                                    // untouched.
                                    RestoreScreenState.Failed(
                                        e.message ?: "Restore failed. Nothing on this device was changed.",
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.start"),
                    ) {
                        Text("Restore From Backup")
                    }
                }
                is RestoreScreenState.Running -> {
                    Text("Restoring…", modifier = Modifier.testTag("backup.restore.running"))
                }
                is RestoreScreenState.Done -> {
                    RestoreSummaryText(current.summary)
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.done")) {
                        Text("Done")
                    }
                }
                is RestoreScreenState.Failed -> {
                    Text(
                        "Nothing was changed. ${current.message}",
                        modifier = Modifier.testTag("backup.restore.failed"),
                    )
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.close")) {
                        Text("Close")
                    }
                }
                is RestoreScreenState.PartiallyRestored -> {
                    Text(
                        "Restore was interrupted. Some messages and chats may already have been added — " +
                            "restoring again is safe and won't duplicate anything. ${current.message}",
                        modifier = Modifier.testTag("backup.restore.partial"),
                    )
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("backup.restore.close_partial")) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestoreSummaryText(summary: BackupRestoreSummary) {
    Text(restoreSummaryText(summary), modifier = Modifier.testTag("backup.restore.summary"))
}

/** Pure — testable without composing anything. */
internal fun restoreSummaryText(summary: BackupRestoreSummary): String {
    val totalWritten = summary.groups + summary.messages + summary.invitations + summary.consents + summary.blobs
    if (totalWritten == 0) {
        // Deliberately not framed as a failure or a warning — an
        // empty result under a different operator or identity is
        // exactly what this looks like when nothing is wrong.
        return "Nothing found for this backup. If you expected history here, check that this is the same " +
            "operator and identity the backup was made under."
    }
    val parts = mutableListOf<String>()
    parts += "Restored ${summary.groups} chat(s) and ${summary.messages} message(s)."
    if (summary.skipped.isNotEmpty()) {
        val skippedText = summary.skipped.entries.joinToString(", ") { (kind, count) -> "$count $kind" }
        parts += "This version of the app couldn't restore: $skippedText."
    }
    if (summary.unresolvedBlobs.isNotEmpty()) {
        parts += "${summary.unresolvedBlobs.size} attachment(s) were referenced by the backup but weren't included in it."
    }
    return parts.joinToString(" ")
}
