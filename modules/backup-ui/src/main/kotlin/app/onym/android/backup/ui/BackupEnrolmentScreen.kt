package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.strings.R

/**
 * The device-backup consent screen. Leads with the operator being
 * enrolled, then, in this order: (1) what backing up does to *other
 * people* in the person's conversations, (2) that nobody — not the
 * operator, not Onym — can recover the archive without the recovery
 * phrase, (3) the honest scheduling sentence. Then the operator's
 * terms in full, unsummarized, from [items]. "Turn On Backup" stays
 * disabled until [hasReachedEnd] is true.
 *
 * Mirrors `BackupEnrolmentView` in onym-ios OnymBackupUI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupEnrolmentScreen(
    operatorName: String,
    items: List<BackupDisclosureItem>,
    scheduleSentence: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reachedEnd by remember {
        derivedStateOf { hasReachedEnd(listState) }
    }

    Scaffold(
        modifier = modifier,
        topBar = { EnrolmentTopBar(onBack) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = true)) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.backup_enrolment_backing_up_to),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            operatorName,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("backup.enrolment.operator"),
                        )
                    }
                }
                item {
                    DisclosureLead(
                        title = stringResource(R.string.backup_enrolment_third_party_title),
                        body = stringResource(R.string.backup_disclosure_third_party_value),
                        modifier = Modifier.testTag("backup.enrolment.third_party"),
                    )
                }
                item {
                    DisclosureLead(
                        title = stringResource(R.string.backup_enrolment_no_reset_title),
                        body = stringResource(R.string.backup_disclosure_recovery_value),
                        modifier = Modifier.testTag("backup.enrolment.no_reset"),
                    )
                }
                item {
                    DisclosureLead(
                        title = stringResource(R.string.backup_enrolment_schedule_title),
                        body = scheduleSentence,
                        modifier = Modifier.testTag("backup.enrolment.schedule"),
                    )
                }
                item { SettingsSectionLabel(stringResource(R.string.backup_section_promises)) }
                item {
                    SettingsCard {
                        items.forEachIndexed { index, item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    item.value,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.testTag("backup.enrolment.term.${item.id}"),
                                )
                            }
                            if (index != items.lastIndex) SettingsHairline(insetStart = 16.dp)
                        }
                    }
                }
                item { SettingsFootnote(stringResource(R.string.backup_footnote_terms_pinned)) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = onAccept,
                        enabled = reachedEnd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backup.enrolment.accept"),
                    ) {
                        Text(stringResource(R.string.backup_enrolment_accept))
                    }
                    TextButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backup.enrolment.decline"),
                    ) {
                        Text(stringResource(R.string.backup_enrolment_decline))
                    }
                }
            }
        }
    }
}

/**
 * What the enrolment destination shows before it has a verified
 * disclosure to render: a progress spinner while the operator's terms
 * are being fetched, or — once a fetch has genuinely failed — the
 * unavailable state with a retry affordance. Mirrors the `.loading`
 * and `.unavailable` states of `BackupEnrolmentView` in onym-ios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupEnrolmentLoadingScreen(
    isFetching: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { EnrolmentTopBar(onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(32.dp)
                .testTag("backup.enrolment.loading"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isFetching) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.backup_enrolment_fetching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.backup_enrolment_unavailable_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("backup.enrolment.fetch_failed"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.backup_enrolment_fetch_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.backup_enrolment_unavailable_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("backup.enrolment.retry"),
                ) {
                    Text(stringResource(R.string.backup_enrolment_retry))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrolmentTopBar(onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.settings_device_backup_row_title)) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("backup.enrolment.back")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
    )
}

@Composable
private fun DisclosureLead(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    SettingsCard(modifier = modifier.padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Pure predicate: has the list been scrolled to its end? Extracted
 * out of the Composable so it's independently testable. Content
 * shorter than the viewport counts as already-read — there's nothing
 * more to scroll to — but that's only knowable AFTER the first
 * measurement pass: `visibleItemsInfo` is also empty before
 * `LazyColumn` has measured anything at all (first composition frame,
 * briefly after a configuration change), and defaulting to `true` in
 * that ambiguous case would let "Turn On Backup" render enabled for a
 * frame before the person has read anything.
 */
internal fun hasReachedEnd(state: LazyListState): Boolean {
    val layoutInfo = state.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return false
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    val isLastItemVisible = lastVisible.index == layoutInfo.totalItemsCount - 1
    val isLastItemFullyVisible = lastVisible.offset + lastVisible.size <= layoutInfo.viewportEndOffset
    return isLastItemVisible && isLastItemFullyVisible
}
