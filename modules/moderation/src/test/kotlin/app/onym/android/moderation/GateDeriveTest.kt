package app.onym.android.moderation

import app.onym.android.moderation.GateCheckRepository.AttemptOutcome
import app.onym.android.moderation.GateCheckRepository.Companion.derive
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The profile-§5.2 grace arithmetic, as a truth table — the literal
 * port of iOS's `GateCheckDeriveTests` plus the Android-only
 * throttled column. */
class GateDeriveTest {
    private val policy = GateCheckPolicy(
        interval = Duration.ofDays(1),
        offlineGrace = Duration.ofDays(3),
    )
    private val now: Instant = Instant.parse("2026-08-08T12:00:00Z")

    private fun persisted(result: GateCheckResult, ageOf: Duration): PersistedGateState =
        PersistedGateState(
            lastResult = result,
            lastSuccessAt = Rfc3339InstantSerializer.format(now.minus(ageOf)),
        )

    @Test
    fun `success persists the result and maps it to a status`() {
        val ban = GateCheckResult.Banned(BanState("v1", authorityContact = "c"))
        val (status, saved) = derive(null, AttemptOutcome.Success(ban), now, policy)
        assertEquals(GateStatus.Banned(ban.ban), status)
        assertEquals(ban, saved?.lastResult)
        assertEquals("2026-08-08T12:00:00Z", saved?.lastSuccessAt)
    }

    @Test
    fun `a refusal blocks immediately and is stamped onto the persisted state`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = Duration.ofHours(1))
        val (status, saved) = derive(
            cached,
            AttemptOutcome.Refused(CheckRequiredReason.BACKEND_REFUSED),
            now,
            policy,
        )
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.BACKEND_REFUSED),
            status,
        )
        // The cached success survives (a later success overwrites it
        // wholesale), but the refusal rides along, sticky.
        assertEquals(
            cached.copy(
                refusalReason = CheckRequiredReason.BACKEND_REFUSED,
                refusedAt = "2026-08-08T12:00:00Z",
            ),
            saved,
        )
    }

    /**
     * The laundering exploit: refuse → force-stop → airplane mode →
     * relaunch. The relaunch derives Unreachable against the persisted
     * state; without the sticky refusal it served the cached Clear for
     * the whole grace window on the say-so of a refusal.
     */
    @Test
    fun `a persisted refusal is not laundered through grace on relaunch`() {
        val (_, afterRefusal) = derive(
            persisted(GateCheckResult.Clear, ageOf = Duration.ofHours(1)),
            AttemptOutcome.Refused(CheckRequiredReason.ENROLLMENT_LOST),
            now,
            policy,
        )
        // Relaunch, offline, well inside grace of the cached Clear.
        val (status, saved) = derive(
            afterRefusal,
            AttemptOutcome.Unreachable,
            now.plusSeconds(3600),
            policy,
        )
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
            status,
        )
        assertEquals(afterRefusal, saved)

        // Throttled attestation must not launder it either.
        val (throttled, _) = derive(
            afterRefusal,
            AttemptOutcome.Throttled,
            now.plusSeconds(3600),
            policy,
        )
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
            throttled,
        )
    }

    /** Only a later successful check clears the sticky refusal. */
    @Test
    fun `a later success clears the persisted refusal`() {
        val (_, afterRefusal) = derive(
            persisted(GateCheckResult.Clear, ageOf = Duration.ofHours(1)),
            AttemptOutcome.Refused(CheckRequiredReason.BACKEND_REFUSED),
            now,
            policy,
        )
        val (status, saved) = derive(
            afterRefusal,
            AttemptOutcome.Success(GateCheckResult.Clear),
            now.plusSeconds(60),
            policy,
        )
        assertEquals(GateStatus.Operational(), status)
        assertEquals(null, saved?.refusalReason)
        assertEquals(null, saved?.refusedAt)

        // And grace serves again afterwards.
        val (later, _) = derive(saved, AttemptOutcome.Unreachable, now.plusSeconds(120), policy)
        assertEquals(GateStatus.Operational(), later)
    }

    @Test
    fun `enrollment lost routes as its own refusal`() {
        val (status, _) = derive(
            null,
            AttemptOutcome.Refused(CheckRequiredReason.ENROLLMENT_LOST),
            now,
            policy,
        )
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
            status,
        )
    }

    @Test
    fun `unreachable inside grace serves the last known state`() {
        val cached = persisted(
            GateCheckResult.CaseOpen(listOf(CaseNotice(caseId = "c1", authority = "a"))),
            ageOf = Duration.ofDays(2),
        )
        val (status, saved) = derive(cached, AttemptOutcome.Unreachable, now, policy)
        assertEquals(GateStatus.Operational((cached.lastResult as GateCheckResult.CaseOpen).notices), status)
        assertEquals(cached, saved)
    }

    /** A cached ban keeps blocking through grace — grace serves the
     * last state, whatever it was; it is not an escape hatch. */
    @Test
    fun `unreachable inside grace also keeps serving a cached ban`() {
        val ban = GateCheckResult.Banned(BanState("v1", authorityContact = "c"))
        val cached = persisted(ban, ageOf = Duration.ofDays(1))
        val (status, _) = derive(cached, AttemptOutcome.Unreachable, now, policy)
        assertEquals(GateStatus.Banned(ban.ban), status)
    }

    @Test
    fun `unreachable past grace blocks with offlineGraceExpired and keeps state`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = Duration.ofDays(4))
        val (status, saved) = derive(cached, AttemptOutcome.Unreachable, now, policy)
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.OFFLINE_GRACE_EXPIRED),
            status,
        )
        assertEquals(cached, saved)
    }

    @Test
    fun `unreachable with no history blocks with neverChecked`() {
        val (status, saved) = derive(null, AttemptOutcome.Unreachable, now, policy)
        assertEquals(GateStatus.GateCheckRequired(CheckRequiredReason.NEVER_CHECKED), status)
        assertNull(saved)
    }

    /** A negative age would satisfy the grace comparison forever, so
     * winding the clock back must block rather than pin a cached
     * clear. */
    @Test
    fun `a clock behind the last success blocks with clockRollback`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = Duration.ofHours(-2))
        val (status, saved) = derive(cached, AttemptOutcome.Unreachable, now, policy)
        assertEquals(GateStatus.GateCheckRequired(CheckRequiredReason.CLOCK_ROLLBACK), status)
        assertEquals(cached, saved)
    }

    @Test
    fun `grace boundary is inclusive`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = policy.offlineGrace)
        val (status, _) = derive(cached, AttemptOutcome.Unreachable, now, policy)
        assertEquals(GateStatus.Operational(), status)
    }

    // ─── Throttled (Android-only column) ─────────────────────────────

    @Test
    fun `throttled inside grace serves the last known state`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = Duration.ofDays(1))
        val (status, _) = derive(cached, AttemptOutcome.Throttled, now, policy)
        assertEquals(GateStatus.Operational(), status)
    }

    @Test
    fun `throttled past grace blocks with attestationUnavailable`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = Duration.ofDays(4))
        val (status, _) = derive(cached, AttemptOutcome.Throttled, now, policy)
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ATTESTATION_UNAVAILABLE),
            status,
        )
    }

    @Test
    fun `throttled with no history blocks with attestationUnavailable`() {
        val (status, _) = derive(null, AttemptOutcome.Throttled, now, policy)
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ATTESTATION_UNAVAILABLE),
            status,
        )
    }

    @Test
    fun `throttled with a rolled-back clock blocks with clockRollback`() {
        val cached = persisted(GateCheckResult.Clear, ageOf = Duration.ofHours(-2))
        val (status, _) = derive(cached, AttemptOutcome.Throttled, now, policy)
        assertEquals(GateStatus.GateCheckRequired(CheckRequiredReason.CLOCK_ROLLBACK), status)
    }
}
