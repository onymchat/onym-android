package app.onym.android.moderation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.moderation.CaseNotice

/**
 * File an appeal against one case, in the app.
 *
 * The authority's `appealUrl` is still on the ban screen and still
 * valid; this is the API route (`POST /v1/cases/{id}/appeal`), which
 * differs in two ways that matter: the statement is signed by the
 * accused's identity key, so the authority can attribute it without a
 * web session, and the filing is retained locally, so an ambiguous
 * delivery is retried with the same signed bytes instead of silently
 * lost.
 *
 * [notice] is the gate's own case notice when the caller has it — it
 * carries the deadlines and the class, which is the context a person
 * needs before writing. Null renders the surface with the case id
 * alone rather than refusing: knowing the id is enough to appeal.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CaseAppealScreen(
    controller: CaseAppealController,
    notice: CaseNotice?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val priorFilings by controller.priorFilings.collectAsStateWithLifecycle()
    // Saveable, not remembered: a long statement must survive a
    // rotation. The controller's own state does not — a rotation after
    // filing shows the form again — but resubmitting the same statement
    // returns the stored receipt rather than filing twice.
    var statement by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.moderation_appeal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // The statement field is the whole screen; without this
                // the keyboard covers what the user is writing.
                .imePadding()
                .padding(24.dp)
                .testTag("moderation.appeal"),
        ) {
            when (val current = state) {
                is CaseAppealController.State.Filed -> {
                    Text(
                        text = stringResource(R.string.moderation_appeal_filed_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("moderation.appeal.filed"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // The authority's own remark when it sent one;
                        // otherwise the neutral "it is on file", which
                        // is all this client can honestly claim.
                        text = current.note
                            ?: stringResource(R.string.moderation_appeal_filed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moderation.appeal.done"),
                    ) { Text(stringResource(R.string.moderation_appeal_done)) }
                }

                else -> {
                    CaseSummary(controller.caseId, notice)
                    // Dedup matches the exact statement, so someone who
                    // comes back and edits one character before
                    // resubmitting files a SECOND appeal. Say so, since
                    // nothing downstream can catch it.
                    if (priorFilings > 0) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.moderation_appeal_already_filed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("moderation.appeal.already_filed"),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.moderation_appeal_statement_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.moderation_appeal_statement_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val bytes = controller.statementBytes(statement)
                    val overCap = bytes > controller.maxStatementBytes
                    val submitting = current is CaseAppealController.State.Submitting
                    OutlinedTextField(
                        value = statement,
                        onValueChange = {
                            statement = it
                            controller.clearFailure()
                        },
                        enabled = !submitting,
                        minLines = 6,
                        isError = overCap,
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.moderation_appeal_statement_bytes,
                                    bytes,
                                    controller.maxStatementBytes,
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moderation.appeal.statement"),
                    )
                    val failure = (current as? CaseAppealController.State.Editing)?.failure
                    if (failure != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = failure.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("moderation.appeal.failure"),
                        )
                        if (failure.recourse == CaseAppealController.Recourse.NONE) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                // Only a genuinely closed door says so.
                                // An outage or an unlisted authority used
                                // to land here too, telling the user to
                                // give up on something that recovers on
                                // its own — and steering them toward
                                // editing the text, which files a
                                // DIFFERENT statement.
                                text = stringResource(R.string.moderation_appeal_terminal_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { controller.submit(statement) },
                        enabled = !submitting &&
                            statement.isNotBlank() &&
                            !overCap &&
                            failure?.recourse != CaseAppealController.Recourse.NONE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moderation.appeal.submit"),
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.moderation_appeal_submit))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.moderation_appeal_signed_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** What the gate told us about the case, when it told us anything. */
@Composable
private fun CaseSummary(caseId: String, notice: CaseNotice?) {
    Text(
        text = stringResource(R.string.moderation_appeal_case_ref, caseId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val classId = notice?.classId?.takeIf { it.isNotBlank() }
    if (classId != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.moderation_appeal_case_class, classId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val deadline = notice?.decisionDeadline?.takeIf { it.isNotBlank() }
    if (deadline != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.moderation_appeal_case_decision_deadline,
                formatDeadline(deadline),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** RFC 3339 rendered for the reader's locale and zone, falling back to
 *  the wire form — same posture as the ban screen's expiry. */
private fun formatDeadline(rfc3339: String): String = try {
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
        .withLocale(java.util.Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(rfc3339))
} catch (_: Exception) {
    rfc3339
}
