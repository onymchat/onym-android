package app.onym.android.backup.ui

import app.onym.android.design.SettingsTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeviceBackupStatusUiTest {

    private val t = Instant.ofEpochSecond(1_000)

    /** The single enrolment predicate everything keys on: the
     *  overview's enrolled count, the Back Up To All fan-out, and the
     *  composition root's enrolment-vs-settings gate. Off has never
     *  been enrolled; TermsChanged/OperatorChanged were, but their
     *  enrolment is no longer valid — all three need the disclosure,
     *  not a Back Up Now that would only fail. */
    @Test
    fun needs_enrolment_exactly_for_off_and_changed_terms_or_operator() {
        assertTrue(needsEnrolment(DeviceBackupStatus.Off))
        assertTrue(needsEnrolment(DeviceBackupStatus.TermsChanged))
        assertTrue(needsEnrolment(DeviceBackupStatus.OperatorChanged))

        assertFalse(needsEnrolment(DeviceBackupStatus.Idle(t)))
        assertFalse(needsEnrolment(DeviceBackupStatus.Idle(null)))
        assertFalse(needsEnrolment(DeviceBackupStatus.Running))
        assertFalse(needsEnrolment(DeviceBackupStatus.Stale(t)))
        assertFalse(needsEnrolment(DeviceBackupStatus.PaymentRequired(listOf("offer"))))
        assertFalse(needsEnrolment(DeviceBackupStatus.CheckingEarlierBackup))
        assertFalse(needsEnrolment(DeviceBackupStatus.Failed("x")))
    }

    /** The tile tints carry the severity read at a glance — pin the
     *  mapping so a refactor can't quietly turn a failure gray. */
    @Test
    fun glyph_tints_encode_severity() {
        assertEquals(SettingsTile.Gray, statusGlyph(DeviceBackupStatus.Off).tint)
        assertEquals(SettingsTile.Green, statusGlyph(DeviceBackupStatus.Idle(t)).tint)
        assertEquals(SettingsTile.Blue, statusGlyph(DeviceBackupStatus.Running).tint)
        assertEquals(SettingsTile.Amber, statusGlyph(DeviceBackupStatus.Stale(t)).tint)
        assertEquals(SettingsTile.Amber, statusGlyph(DeviceBackupStatus.PaymentRequired(emptyList())).tint)
        assertEquals(SettingsTile.Amber, statusGlyph(DeviceBackupStatus.CheckingEarlierBackup).tint)
        assertEquals(SettingsTile.Orange, statusGlyph(DeviceBackupStatus.TermsChanged).tint)
        assertEquals(SettingsTile.Orange, statusGlyph(DeviceBackupStatus.OperatorChanged).tint)
        assertEquals(SettingsTile.Red, statusGlyph(DeviceBackupStatus.Failed("x")).tint)
    }
}
