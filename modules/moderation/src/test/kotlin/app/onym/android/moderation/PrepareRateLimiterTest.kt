package app.onym.android.moderation

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google's cap: at most five provider preparations per app instance
 * per minute (profile §5.2). */
class PrepareRateLimiterTest {
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z")

    @Test
    fun `the sixth preparation inside a minute is refused`() {
        val limiter = PrepareRateLimiter(clock = { now })
        repeat(5) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `the window slides`() {
        val limiter = PrepareRateLimiter(clock = { now })
        repeat(5) { assertTrue(limiter.tryAcquire()) }
        now = now.plusSeconds(61)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `a refusal does not consume a slot`() {
        val limiter = PrepareRateLimiter(clock = { now })
        repeat(5) { limiter.tryAcquire() }
        repeat(10) { assertFalse(limiter.tryAcquire()) }
        now = now.plusSeconds(61)
        assertTrue(limiter.tryAcquire())
    }
}
