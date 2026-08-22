package app.onym.android.backup.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.onym.android.design.SettingsTile
import app.onym.android.strings.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A status's tile rendering alone — for the places (aggregate
 *  summary row, operator rows) that pick the glyph for a status while
 *  composing their own text. Pure, so the icon/tint mapping is
 *  testable without composition. */
internal data class StatusGlyph(
    val icon: ImageVector,
    val tint: Color,
)

internal fun statusGlyph(status: DeviceBackupStatus): StatusGlyph = when (status) {
    is DeviceBackupStatus.Off -> StatusGlyph(Icons.Filled.Storage, SettingsTile.Gray)
    is DeviceBackupStatus.Idle -> StatusGlyph(Icons.Filled.CloudDone, SettingsTile.Green)
    is DeviceBackupStatus.Running -> StatusGlyph(Icons.Filled.Sync, SettingsTile.Blue)
    is DeviceBackupStatus.Stale -> StatusGlyph(Icons.Filled.WarningAmber, SettingsTile.Amber)
    is DeviceBackupStatus.PaymentRequired -> StatusGlyph(Icons.Filled.CreditCard, SettingsTile.Amber)
    is DeviceBackupStatus.TermsChanged -> StatusGlyph(Icons.AutoMirrored.Filled.Article, SettingsTile.Orange)
    is DeviceBackupStatus.OperatorChanged -> StatusGlyph(Icons.Filled.SwapHoriz, SettingsTile.Orange)
    is DeviceBackupStatus.CheckingEarlierBackup -> StatusGlyph(Icons.Filled.HelpOutline, SettingsTile.Amber)
    is DeviceBackupStatus.Failed -> StatusGlyph(Icons.Filled.Dangerous, SettingsTile.Red)
}

/** One status's rendering on the per-operator card: title/subtitle
 *  pair plus the tile glyph — the Android shape of the status table in
 *  onym-ios `DeviceBackupSettingsView`. */
internal data class StatusPresentation(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
)

@Composable
internal fun statusPresentation(status: DeviceBackupStatus): StatusPresentation {
    val glyph = statusGlyph(status)
    val (title, subtitle) = when (status) {
        is DeviceBackupStatus.Off ->
            stringResource(R.string.backup_status_off) to
                stringResource(R.string.backup_status_off_subtitle)
        is DeviceBackupStatus.Idle ->
            stringResource(R.string.backup_status_on_title) to (
                status.lastSuccessAt
                    ?.let { stringResource(R.string.backup_status_last_backup, formatBackupInstant(it)) }
                    ?: stringResource(R.string.backup_status_none_completed)
                )
        is DeviceBackupStatus.Running ->
            stringResource(R.string.backup_status_backing_up) to
                stringResource(R.string.backup_status_uploading)
        is DeviceBackupStatus.Stale ->
            stringResource(R.string.backup_status_out_of_date_title) to (
                status.lastSuccessAt
                    ?.let { stringResource(R.string.backup_status_last_backup, formatBackupInstant(it)) }
                    ?: stringResource(R.string.backup_status_none_completed)
                )
        is DeviceBackupStatus.PaymentRequired ->
            stringResource(R.string.backup_status_payment_title) to
                stringResource(R.string.backup_status_payment_subtitle)
        is DeviceBackupStatus.TermsChanged ->
            stringResource(R.string.backup_status_terms_title) to
                stringResource(R.string.backup_status_terms_subtitle)
        is DeviceBackupStatus.OperatorChanged ->
            stringResource(R.string.backup_status_operator_title) to
                stringResource(R.string.backup_status_operator_subtitle)
        is DeviceBackupStatus.CheckingEarlierBackup ->
            stringResource(R.string.backup_status_checking_title) to
                stringResource(R.string.backup_status_checking_subtitle)
        is DeviceBackupStatus.Failed ->
            stringResource(R.string.backup_status_failed_title) to status.message
    }
    return StatusPresentation(title, subtitle, glyph.icon, glyph.tint)
}

/** The compact lowercase phrase for an operator row's subtitle on the
 *  overview screen — mirrors the `statusPhrase` table in onym-ios
 *  `DeviceBackupVendorsView`. */
@Composable
internal fun statusPhrase(status: DeviceBackupStatus): String = when (status) {
    is DeviceBackupStatus.Off -> stringResource(R.string.backup_phrase_not_set_up)
    is DeviceBackupStatus.Idle -> status.lastSuccessAt
        ?.let { stringResource(R.string.backup_phrase_backed_up, formatBackupInstant(it)) }
        ?: stringResource(R.string.backup_phrase_nothing_uploaded)
    is DeviceBackupStatus.Running -> stringResource(R.string.backup_phrase_backing_up)
    is DeviceBackupStatus.Stale -> stringResource(R.string.backup_phrase_out_of_date)
    is DeviceBackupStatus.PaymentRequired -> stringResource(R.string.backup_phrase_payment_needed)
    is DeviceBackupStatus.TermsChanged -> stringResource(R.string.backup_phrase_new_terms)
    is DeviceBackupStatus.OperatorChanged -> stringResource(R.string.backup_phrase_needs_setup_again)
    is DeviceBackupStatus.CheckingEarlierBackup -> stringResource(R.string.backup_phrase_checking_earlier)
    is DeviceBackupStatus.Failed -> stringResource(R.string.backup_phrase_something_wrong)
}

internal fun formatBackupInstant(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)

/** An operator that is Off, or whose terms/operator changed, has no
 *  valid enrolment right now — same predicate as `needsEnrolment` in
 *  onym-ios `DeviceBackupSettingsFlow`. Public because the
 *  composition root gates the enrolment-vs-settings destination on
 *  the SAME predicate the overview uses to count enrolled operators —
 *  two predicates would let the overview exclude an operator that the
 *  settings screen still offers a working "Back Up Now". */
fun needsEnrolment(status: DeviceBackupStatus): Boolean = when (status) {
    is DeviceBackupStatus.Off,
    is DeviceBackupStatus.TermsChanged,
    is DeviceBackupStatus.OperatorChanged,
    -> true
    else -> false
}

/** The aggregate STATUS card at the top of the overview screen —
 *  mirrors `Summary` in onym-ios `DeviceBackupVendorsFlow`, computed
 *  pessimistically: running wins, then needs-attention, then on, then
 *  off. Pure, so it is testable without composing anything. */
internal sealed class BackupVendorsSummary {
    data object Off : BackupVendorsSummary()
    data class On(
        val enrolled: Int,
        val oldestCopy: Instant?,
        val notSetUp: Int,
    ) : BackupVendorsSummary()
    data object Running : BackupVendorsSummary()
    data class NeedsAttention(val attention: Int, val healthy: Int) : BackupVendorsSummary()
}

internal fun summarize(statuses: List<DeviceBackupStatus>): BackupVendorsSummary {
    if (statuses.any { it is DeviceBackupStatus.Running }) return BackupVendorsSummary.Running
    val attention = statuses.count {
        when (it) {
            is DeviceBackupStatus.Stale,
            is DeviceBackupStatus.PaymentRequired,
            is DeviceBackupStatus.TermsChanged,
            is DeviceBackupStatus.OperatorChanged,
            is DeviceBackupStatus.CheckingEarlierBackup,
            is DeviceBackupStatus.Failed,
            -> true
            else -> false
        }
    }
    if (attention > 0) {
        return BackupVendorsSummary.NeedsAttention(
            attention = attention,
            healthy = statuses.count { it is DeviceBackupStatus.Idle },
        )
    }
    val enrolled = statuses.count { !needsEnrolment(it) }
    if (enrolled == 0) return BackupVendorsSummary.Off
    // No attention and no running here, so every enrolled operator is
    // Idle — the oldest completed copy is the pessimistic read of how
    // much history is actually held everywhere. An Idle operator with
    // NO completed backup is strictly older than any instant: it
    // forces the "no backup has completed yet" read instead of a
    // green summary that hides the operator holding nothing.
    val idleTimes = statuses
        .filterIsInstance<DeviceBackupStatus.Idle>()
        .map { it.lastSuccessAt }
    val oldest = if (idleTimes.any { it == null }) {
        null
    } else {
        idleTimes.filterNotNull().minOrNull()
    }
    return BackupVendorsSummary.On(
        enrolled = enrolled,
        oldestCopy = oldest,
        notSetUp = statuses.count { it is DeviceBackupStatus.Off },
    )
}
