package app.onym.android.backup.ui

import app.onym.android.backup.BackupRestoreSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreScreenTest {

    @Test
    fun empty_result_reads_as_an_ordinary_answer_not_an_error() {
        val summary = BackupRestoreSummary(0, 0, 0, 0, 0, emptyMap(), emptyList())
        val text = restoreSummaryText(summary)
        assertFalse(text.contains("error", ignoreCase = true))
        assertFalse(text.contains("gone", ignoreCase = true))
        assertFalse(text.contains("failed", ignoreCase = true))
        assertTrue(text.contains("Nothing found"))
    }

    @Test
    fun nonempty_result_reports_what_was_written() {
        val summary = BackupRestoreSummary(2, 10, 0, 1, 0, emptyMap(), emptyList())
        val text = restoreSummaryText(summary)
        assertTrue(text.contains("2 chat"))
        assertTrue(text.contains("10 message"))
    }

    @Test
    fun skipped_rows_and_unresolved_blobs_are_reported_distinctly() {
        val summary = BackupRestoreSummary(1, 1, 0, 0, 0, mapOf("groups" to 1), listOf("sha256hex"))
        val text = restoreSummaryText(summary)
        assertTrue(text.contains("couldn't restore"))
        assertTrue(text.contains("1 attachment"))
    }
}
