package app.onym.android.backup.ui

import app.onym.android.foundation.StringProvider
import app.onym.android.strings.R
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/** Conditions an opportunistic in-app backup check is gated on. */
data class BackupRunConditions(
    val isOnWifi: Boolean,
    val isCharging: Boolean,
)

/**
 * The settings a device backup actually runs under — one struct, not
 * scattered UI state. There is deliberately **no background
 * scheduling** in this version (no periodic WorkManager job): only an
 * in-app, foreground, opportunistic check while the app happens to be
 * open ([permitsOpportunisticRun]), plus an explicit "Back Up Now"
 * that bypasses the schedule entirely. Snapshots are always whole,
 * never incremental — the onym-system profile's own stated known gap.
 *
 * Mirrors `BackupSchedule` in onym-ios OnymBackupUI.
 */
data class BackupSchedule(
    val minimumInterval: Duration = Duration.ofHours(24),
    val maximumJitter: Duration = Duration.ofHours(4),
    val requiresWifi: Boolean = true,
    val requiresCharging: Boolean = true,
    val stalenessThreshold: Duration = Duration.ofDays(7),
) {
    /** Draw once per app session — passed in by the caller, which
     *  holds it for the process lifetime — and NOT re-drawn per check.
     *  Re-drawing on every check would let repeated polling sample
     *  toward a small jitter value, defeating the reason jitter exists
     *  (so an observer of upload timing can't infer the exact schedule
     *  boundary). */
    fun drawJitter(random: SecureRandom = SecureRandom()): Duration {
        if (maximumJitter.isZero || maximumJitter.isNegative) return Duration.ZERO
        val maxMillis = maximumJitter.toMillis().coerceAtLeast(1)
        return Duration.ofMillis((random.nextDouble() * maxMillis).toLong())
    }

    /** Whether an opportunistic (non-explicit) run is permitted right
     *  now, given [jitter] drawn once for this session. */
    fun permitsOpportunisticRun(
        conditions: BackupRunConditions,
        jitter: Duration,
        lastSuccessAt: Instant?,
        now: Instant = Instant.now(),
    ): Boolean {
        if (requiresWifi && !conditions.isOnWifi) return false
        if (requiresCharging && !conditions.isCharging) return false
        if (lastSuccessAt == null) return true
        val elapsed = Duration.between(lastSuccessAt, now)
        return elapsed >= minimumInterval.plus(jitter)
    }

    fun isStale(lastSuccessAt: Instant?, now: Instant = Instant.now()): Boolean {
        if (lastSuccessAt == null) return false
        return Duration.between(lastSuccessAt, now) >= stalenessThreshold
    }

    /** The honest scheduling sentence for the consent surface —
     *  computed from the live schedule, never a hardcoded claim of
     *  "automatically." A minimum-interval-only phrasing understates
     *  what actually happens (foreground-only, condition-gated), so
     *  this states the real conditions plainly.
     *
     *  Four pre-composed templates, not fragment concatenation — see
     *  the string resources' own comment for why gluing localized
     *  "on Wi-Fi"/"while charging" fragments together isn't a safe
     *  general i18n pattern even though it happens to read fine in
     *  English. */
    fun disclosureSentence(strings: StringProvider): String {
        val hours = minimumInterval.toHours().toInt()
        val resId = when {
            requiresWifi && requiresCharging -> R.string.backup_schedule_both_conditions
            requiresWifi -> R.string.backup_schedule_wifi_only
            requiresCharging -> R.string.backup_schedule_charging_only
            else -> R.string.backup_schedule_no_conditions
        }
        return strings.get(resId, hours)
    }
}
