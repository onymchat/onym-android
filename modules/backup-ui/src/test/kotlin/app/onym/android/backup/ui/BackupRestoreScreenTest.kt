package app.onym.android.backup.ui

import app.onym.android.backup.BackupRestoreSummary
import app.onym.android.foundation.StringProvider
import app.onym.android.strings.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** English-only fake, mirroring `modules/strings/src/main/res/values/strings.xml`
 *  exactly enough for these assertions — no Robolectric/real resource
 *  resolution needed for a pure-JVM test of the branching logic. */
private class FakeStringProvider : StringProvider {
    override fun get(resId: Int): String = when (resId) {
        R.string.backup_restore_nothing_found ->
            "Nothing found for this backup. If you expected history here, check that this is the same " +
                "operator and identity the backup was made under."
        else -> "resId=$resId"
    }

    override fun get(resId: Int, vararg formatArgs: Any): String = when (resId) {
        R.string.backup_restore_summary_template -> "Restored ${formatArgs[0]} and ${formatArgs[1]}."
        R.string.backup_restore_skipped -> "This version of the app couldn't restore: ${formatArgs[0]}."
        else -> "resId=$resId args=${formatArgs.joinToString()}"
    }

    override fun getQuantity(pluralsResId: Int, quantity: Int, vararg formatArgs: Any): String = when (pluralsResId) {
        R.plurals.backup_restore_chats_count -> "$quantity chat" + if (quantity == 1) "" else "s"
        R.plurals.backup_restore_messages_count -> "$quantity message" + if (quantity == 1) "" else "s"
        R.plurals.backup_restore_unresolved_blobs ->
            "$quantity attachment" + (if (quantity == 1) " was" else "s were") +
                " referenced by the backup but " + (if (quantity == 1) "wasn't" else "weren't") + " included in it."
        else -> "pluralsResId=$pluralsResId quantity=$quantity"
    }
}

class BackupRestoreScreenTest {

    private val strings = FakeStringProvider()

    @Test
    fun empty_result_reads_as_an_ordinary_answer_not_an_error() {
        val summary = BackupRestoreSummary(0, 0, 0, 0, 0, emptyMap(), emptyList())
        val text = restoreSummaryText(summary, strings)
        assertFalse(text.contains("error", ignoreCase = true))
        assertFalse(text.contains("gone", ignoreCase = true))
        assertFalse(text.contains("failed", ignoreCase = true))
        assertTrue(text.contains("Nothing found"))
    }

    @Test
    fun nonempty_result_reports_what_was_written() {
        val summary = BackupRestoreSummary(2, 10, 0, 1, 0, emptyMap(), emptyList())
        val text = restoreSummaryText(summary, strings)
        assertTrue(text.contains("2 chat"))
        assertTrue(text.contains("10 message"))
    }

    @Test
    fun skipped_rows_and_unresolved_blobs_are_reported_distinctly() {
        val summary = BackupRestoreSummary(1, 1, 0, 0, 0, mapOf("groups" to 1), listOf("sha256hex"))
        val text = restoreSummaryText(summary, strings)
        assertTrue(text.contains("couldn't restore"))
        assertTrue(text.contains("1 attachment"))
    }
}
