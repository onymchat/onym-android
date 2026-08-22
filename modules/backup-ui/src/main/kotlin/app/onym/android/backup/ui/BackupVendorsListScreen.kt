package app.onym.android.backup.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.strings.R

/** One vendor row's display data — a plain snapshot, not a live
 *  dependency bundle, so this screen doesn't need to know how a
 *  vendor's status is produced. */
data class BackupVendorRow(
    val componentId: String,
    val displayName: String,
    val status: DeviceBackupStatus,
)

/**
 * Settings → Device Backup: the overview of every backup operator the
 * holder is consented to — a holder may back up to several at once,
 * each under its own seed-derived key material (see `BackupSeat`'s doc
 * comment on the app side). Leads with the aggregate STATUS card, then
 * the back-up-to-all action, then one row per operator. Tapping a row
 * opens that operator's own settings/enrolment screen.
 *
 * This is also where a holder ACQUIRES an operator: [catalogSection] is
 * the discovery-backed "from catalog" list, rendered below whatever is
 * already consented. Without it this screen can only ever show the
 * empty state — nothing else in the app offers a `storage.backup`
 * operator to consent to, so the seat would be a settings section that
 * appears only for holders who somehow already had a pinned consent.
 *
 * Mirrors `DeviceBackupVendorsView` in onym-ios OnymBackupUI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupVendorsListScreen(
    vendors: List<BackupVendorRow>,
    onVendorClick: (componentId: String) -> Unit,
    onBackUpAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The operators discovery is offering for this seat, as a slot:
     *  :backup-ui has no :discovery dependency and no business gaining
     *  one — the composition root renders the catalog rows with the
     *  same atoms every other seat's picker uses. Null (the default)
     *  renders the screen exactly as it did before discovery reached
     *  this seat, which is what the UI-test harness without discovery
     *  fakes sees. */
    catalogSection: (@Composable () -> Unit)? = null,
) {
    val summary = summarize(vendors.map { it.status })
    val enrolledCount = vendors.count { !needsEnrolment(it.status) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_device_backup_row_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backup.vendors.back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        // One list either way: the catalog section has to be reachable
        // in BOTH states — a holder with no vendor needs it to get a
        // first one, and a holder with one needs it to add a second
        // (this seat is multi-vendor by construction).
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("backup.vendors.list")) {
            if (vendors.isEmpty()) {
                item {
                    SettingsFootnote(
                        stringResource(R.string.backup_vendors_empty),
                        modifier = Modifier.testTag("backup.vendors.empty"),
                    )
                }
            } else {
                item { SettingsSectionLabel(stringResource(R.string.backup_section_status)) }
                item {
                    SettingsCard {
                        SummaryRow(summary, modifier = Modifier.testTag("backup.vendors.status"))
                    }
                }
                item { SettingsFootnote(summaryFootnote(summary)) }

                if (enrolledCount > 0) {
                    item {
                        SettingsCard {
                            val running = summary is BackupVendorsSummary.Running
                            SettingsRow(
                                leading = { SettingsTileBox(Icons.Filled.CloudUpload, SettingsTile.Blue) },
                                title = if (enrolledCount > 1) {
                                    stringResource(R.string.backup_back_up_all_title)
                                } else {
                                    stringResource(R.string.backup_settings_back_up_now)
                                },
                                subtitle = if (enrolledCount > 1) {
                                    pluralStringResource(
                                        R.plurals.backup_back_up_all_subtitle,
                                        enrolledCount,
                                        enrolledCount,
                                    )
                                } else {
                                    stringResource(R.string.backup_back_up_now_subtitle)
                                },
                                titleColor = if (running) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                onClick = if (running) null else onBackUpAll,
                                showChevron = false,
                                isLast = true,
                                modifier = Modifier.testTag("backup.vendors.back_up_all"),
                            )
                        }
                    }
                    item { SettingsFootnote(stringResource(R.string.backup_footnote_full_upload)) }
                }

                item { SettingsSectionLabel(stringResource(R.string.backup_section_operators)) }
                item {
                    SettingsCard {
                        vendors.forEachIndexed { index, vendor ->
                            val presentation = statusPresentation(vendor.status)
                            SettingsRow(
                                leading = { SettingsTileBox(presentation.icon, presentation.tint) },
                                title = stringResource(R.string.backup_operator_row_title),
                                subtitle = "${vendor.displayName} · ${statusPhrase(vendor.status)}",
                                onClick = { onVendorClick(vendor.componentId) },
                                isLast = index == vendors.lastIndex,
                                modifier = Modifier.testTag("backup.vendors.row.${vendor.componentId}"),
                            )
                        }
                    }
                }
                item { SettingsFootnote(stringResource(R.string.backup_footnote_operators)) }
            }
            catalogSection?.let { section -> item { section() } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SummaryRow(summary: BackupVendorsSummary, modifier: Modifier = Modifier) {
    when (summary) {
        is BackupVendorsSummary.Off -> SettingsRow(
            leading = { SettingsTileBox(Icons.Filled.CloudUpload, SettingsTile.Gray) },
            title = stringResource(R.string.backup_status_off),
            subtitle = stringResource(R.string.backup_summary_off_consented_subtitle),
            isLast = true,
            modifier = modifier,
        )
        is BackupVendorsSummary.On -> {
            val presentation = statusPresentation(
                DeviceBackupStatus.Idle(summary.oldestCopy),
            )
            val parts = mutableListOf(
                pluralStringResource(
                    R.plurals.backup_summary_operators_set_up,
                    summary.enrolled,
                    summary.enrolled,
                ),
                summary.oldestCopy
                    ?.let { stringResource(R.string.backup_summary_oldest_copy, formatBackupInstant(it)) }
                    ?: stringResource(R.string.backup_summary_none_completed),
            )
            if (summary.notSetUp > 0) {
                parts += pluralStringResource(
                    R.plurals.backup_summary_more_not_set_up,
                    summary.notSetUp,
                    summary.notSetUp,
                )
            }
            SettingsRow(
                leading = { SettingsTileBox(presentation.icon, presentation.tint) },
                title = stringResource(R.string.backup_status_on_title),
                subtitle = parts.joinToString(" · "),
                isLast = true,
                modifier = modifier,
            )
        }
        is BackupVendorsSummary.Running -> {
            val presentation = statusPresentation(DeviceBackupStatus.Running)
            SettingsRow(
                leading = { SettingsTileBox(presentation.icon, presentation.tint) },
                title = presentation.title,
                subtitle = presentation.subtitle,
                isLast = true,
                modifier = modifier,
            )
        }
        is BackupVendorsSummary.NeedsAttention -> {
            val attentionPart = pluralStringResource(
                R.plurals.backup_summary_attention_count,
                summary.attention,
                summary.attention,
            )
            val subtitle = if (summary.healthy > 0) {
                pluralStringResource(
                    R.plurals.backup_summary_healthy_prefix,
                    summary.healthy,
                    summary.healthy,
                ) + " · " + attentionPart
            } else {
                attentionPart
            }
            val presentation = statusPresentation(DeviceBackupStatus.Stale(null))
            SettingsRow(
                leading = { SettingsTileBox(presentation.icon, presentation.tint) },
                title = stringResource(R.string.backup_summary_attention_title),
                subtitle = subtitle,
                isLast = true,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun summaryFootnote(summary: BackupVendorsSummary): String = when (summary) {
    is BackupVendorsSummary.Off -> stringResource(R.string.backup_footnote_sealed)
    is BackupVendorsSummary.NeedsAttention -> stringResource(R.string.backup_footnote_attention)
    else -> stringResource(R.string.backup_footnote_manual)
}
