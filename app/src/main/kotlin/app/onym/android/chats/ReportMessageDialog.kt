package app.onym.android.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.strings.R
import java.util.Locale

/**
 * The report-to-authority sheet: shows exactly what will be disclosed,
 * offers the consented violation classes (no preselection — the first
 * manifest class is often the gravest, and a default there invites
 * mis-filings), and renders the filing outcome. Modeled on
 * [FailedMediaActionSheet]'s AlertDialog shape; mirrors iOS
 * `ModerationReportView`.
 */
@Composable
internal fun ReportMessageDialog(
    state: ReportUiState,
    onSelectClass: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is ReportUiState.Preparing -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chat_report_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = stringResource(R.string.chat_report_preparing),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
            modifier = Modifier.testTag("chat_thread.report.preparing"),
        )

        is ReportUiState.Unavailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chat_report_title)) },
            text = { Text(stringResource(R.string.chat_report_unavailable)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            },
            modifier = Modifier.testTag("chat_thread.report.unavailable"),
        )

        is ReportUiState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chat_report_filed_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.alreadyFiled) {
                            R.string.chat_report_already_filed
                        } else {
                            R.string.chat_report_filed_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("chat_thread.report.done"),
                ) { Text(stringResource(R.string.done)) }
            },
            modifier = Modifier.testTag("chat_thread.report.success"),
        )

        is ReportUiState.Form -> AlertDialog(
            onDismissRequest = { if (!state.submitting) onDismiss() },
            title = { Text(stringResource(R.string.chat_report_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(
                            if (state.hasPhoto) {
                                R.string.chat_report_disclose_photo
                            } else {
                                R.string.chat_report_disclose_text
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.displayBody.isNotEmpty()) {
                        Text(
                            text = state.displayBody,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    Column(modifier = Modifier.selectableGroup()) {
                        state.classes.forEach { violationClass ->
                            val selected = violationClass.classId == state.selectedClassId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        enabled = !state.submitting,
                                        onClick = { onSelectClass(violationClass.classId) },
                                    )
                                    .padding(vertical = 8.dp)
                                    .testTag("chat_thread.report.class.${violationClass.classId}"),
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    text = displayName(violationClass.classId),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            if (selected) {
                                val definition = violationClass.definition
                                if (definition.isNotBlank()) {
                                    Text(
                                        text = definition,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .padding(start = 40.dp, bottom = 4.dp)
                                            .testTag("chat_thread.report.definition"),
                                    )
                                }
                                val lawful = violationClass.lawfulReporting
                                if (!lawful.isNullOrBlank()) {
                                    Text(
                                        text = lawful,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier
                                            .padding(start = 40.dp, bottom = 4.dp)
                                            .testTag("chat_thread.report.lawful"),
                                    )
                                }
                            }
                        }
                    }
                    state.error?.let { error ->
                        Text(
                            text = errorText(error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .testTag("chat_thread.report.error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onSubmit,
                    enabled = state.selectedClassId != null && !state.submitting,
                    modifier = Modifier.testTag("chat_thread.report.submit"),
                ) {
                    Text(stringResource(R.string.chat_report_submit))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.submitting,
                ) { Text(stringResource(R.string.cancel)) }
            },
            modifier = Modifier.testTag("chat_thread.report.form"),
        )
    }
}

@Composable
private fun errorText(error: ReportError): String = when (error) {
    ReportError.NoValidProof -> stringResource(R.string.chat_report_error_no_proof)
    ReportError.MandateRequired -> stringResource(R.string.chat_report_error_mandate)
    ReportError.AuthorityUnavailable -> stringResource(R.string.chat_report_error_authority)
    ReportError.Delivery -> stringResource(R.string.chat_report_error_delivery)
    is ReportError.Rejected -> error.message
}

/** Typography only, never a rename: split on `-`, capitalize the first
 *  word, uppercase the known acronyms. iOS parity. */
private fun displayName(classId: String): String =
    classId.split('-').joinToString(" ") { word ->
        when (word.lowercase(Locale.ROOT)) {
            "csam", "ncii" -> word.uppercase(Locale.ROOT)
            else -> word
        }
    }.replaceFirstChar { it.titlecase(Locale.ROOT) }
