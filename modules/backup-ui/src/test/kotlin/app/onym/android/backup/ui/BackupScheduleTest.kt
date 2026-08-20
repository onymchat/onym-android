package app.onym.android.backup.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class BackupScheduleTest {

    @Test
    fun disclosure_sentence_never_claims_automatic() {
        val schedule = BackupSchedule()
        assertFalse(schedule.disclosureSentence().contains("automatic", ignoreCase = true))
    }

    @Test
    fun opportunistic_run_respects_wifi_and_charging_requirements() {
        val schedule = BackupSchedule(requiresWifi = true, requiresCharging = true)
        val now = Instant.now()
        assertFalse(
            schedule.permitsOpportunisticRun(
                BackupRunConditions(isOnWifi = false, isCharging = true),
                jitter = Duration.ZERO,
                lastSuccessAt = null,
                now = now,
            ),
        )
        assertTrue(
            schedule.permitsOpportunisticRun(
                BackupRunConditions(isOnWifi = true, isCharging = true),
                jitter = Duration.ZERO,
                lastSuccessAt = null,
                now = now,
            ),
        )
    }

    @Test
    fun opportunistic_run_waits_out_the_minimum_interval_plus_jitter() {
        val schedule = BackupSchedule(minimumInterval = Duration.ofHours(24), requiresWifi = false, requiresCharging = false)
        val now = Instant.now()
        val lastSuccess = now.minus(Duration.ofHours(23))
        assertFalse(
            schedule.permitsOpportunisticRun(
                BackupRunConditions(isOnWifi = true, isCharging = true),
                jitter = Duration.ofHours(2),
                lastSuccessAt = lastSuccess,
                now = now,
            ),
        )
        val longerAgo = now.minus(Duration.ofHours(27))
        assertTrue(
            schedule.permitsOpportunisticRun(
                BackupRunConditions(isOnWifi = true, isCharging = true),
                jitter = Duration.ofHours(2),
                lastSuccessAt = longerAgo,
                now = now,
            ),
        )
    }

    @Test
    fun staleness_is_measured_against_the_threshold() {
        val schedule = BackupSchedule(stalenessThreshold = Duration.ofDays(7))
        val now = Instant.now()
        assertFalse(schedule.isStale(now.minus(Duration.ofDays(1)), now))
        assertTrue(schedule.isStale(now.minus(Duration.ofDays(8)), now))
    }
}
