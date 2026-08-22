package app.onym.android.backup.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The overview screen's aggregate STATUS card is computed
 * pessimistically, mirroring `Summary` in onym-ios
 * `DeviceBackupVendorsFlow`: running wins, then needs-attention, then
 * on, then off.
 */
class BackupVendorsSummaryTest {

    private val t1 = Instant.ofEpochSecond(1_000)
    private val t2 = Instant.ofEpochSecond(2_000)

    @Test
    fun no_operators_reads_as_off() {
        assertEquals(BackupVendorsSummary.Off, summarize(emptyList()))
    }

    @Test
    fun consented_but_not_enrolled_reads_as_off() {
        assertEquals(
            BackupVendorsSummary.Off,
            summarize(listOf(DeviceBackupStatus.Off, DeviceBackupStatus.Off)),
        )
    }

    @Test
    fun running_outranks_everything() {
        assertEquals(
            BackupVendorsSummary.Running,
            summarize(
                listOf(
                    DeviceBackupStatus.Failed("x"),
                    DeviceBackupStatus.Running,
                    DeviceBackupStatus.Idle(t1),
                ),
            ),
        )
    }

    @Test
    fun any_unhealthy_operator_reads_as_needs_attention_with_healthy_count() {
        assertEquals(
            BackupVendorsSummary.NeedsAttention(attention = 2, healthy = 1),
            summarize(
                listOf(
                    DeviceBackupStatus.Idle(t1),
                    DeviceBackupStatus.Stale(t1),
                    DeviceBackupStatus.PaymentRequired(listOf("offer")),
                ),
            ),
        )
    }

    @Test
    fun all_idle_reads_as_on_with_the_oldest_copy() {
        assertEquals(
            BackupVendorsSummary.On(enrolled = 2, oldestCopy = t1, notSetUp = 1),
            summarize(
                listOf(
                    DeviceBackupStatus.Idle(t2),
                    DeviceBackupStatus.Idle(t1),
                    DeviceBackupStatus.Off,
                ),
            ),
        )
    }

    @Test
    fun enrolled_with_no_completed_backup_reports_no_oldest_copy() {
        assertEquals(
            BackupVendorsSummary.On(enrolled = 1, oldestCopy = null, notSetUp = 0),
            summarize(listOf(DeviceBackupStatus.Idle(null))),
        )
    }

    @Test
    fun an_idle_operator_holding_nothing_is_not_counted_as_up_to_date() {
        // Same pessimism as the oldest-copy read: set up with no
        // completed backup means holding nothing, and "up to date"
        // would overstate it.
        assertEquals(
            BackupVendorsSummary.NeedsAttention(attention = 1, healthy = 1),
            summarize(
                listOf(
                    DeviceBackupStatus.Idle(t1),
                    DeviceBackupStatus.Idle(null),
                    DeviceBackupStatus.Failed("x"),
                ),
            ),
        )
    }

    @Test
    fun one_operator_holding_nothing_forces_the_no_completed_read() {
        // An Idle operator with no completed backup is strictly older
        // than any instant — a green "oldest copy <t2>" would hide
        // that one operator holds nothing at all.
        assertEquals(
            BackupVendorsSummary.On(enrolled = 2, oldestCopy = null, notSetUp = 0),
            summarize(listOf(DeviceBackupStatus.Idle(t2), DeviceBackupStatus.Idle(null))),
        )
    }

    @Test
    fun changed_terms_or_operator_need_attention_not_quiet_off() {
        // Both statuses mean "no valid enrolment right now", but unlike
        // plain Off the holder has something to act on — burying them
        // in an Off summary would hide that.
        assertEquals(
            BackupVendorsSummary.NeedsAttention(attention = 2, healthy = 0),
            summarize(listOf(DeviceBackupStatus.TermsChanged, DeviceBackupStatus.OperatorChanged)),
        )
    }
}
