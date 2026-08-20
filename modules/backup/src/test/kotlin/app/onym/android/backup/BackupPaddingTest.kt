package app.onym.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPaddingTest {

    @Test
    fun worked_example_matches_the_pinned_profile() {
        assertEquals(41_943_040L, BackupPadding.paddedLength(41_000_000L))
    }

    @Test
    fun overhead_never_exceeds_twelve_percent() {
        var length = 1_000L
        while (length < 5_000_000L) {
            val padded = BackupPadding.paddedLength(length)
            val overhead = (padded - length).toDouble() / length.toDouble()
            assertTrue("overhead $overhead too large at length $length", overhead <= 0.12)
            length += 7_919L
        }
    }

    @Test
    fun small_lengths_pass_through_unpadded() {
        assertEquals(0L, BackupPadding.paddedLength(0L))
        assertEquals(1L, BackupPadding.paddedLength(1L))
        assertEquals(2L, BackupPadding.paddedLength(2L))
    }
}
