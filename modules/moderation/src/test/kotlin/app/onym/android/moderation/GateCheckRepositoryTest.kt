package app.onym.android.moderation

import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryGateStateStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCheckRepositoryTest {
    private val backend = ScriptedEnforcementBackendClient()
    private val attestation = FakeDeviceAttestationProvider()
    private val signer = FakeModerationSigner()
    private val gateStore = InMemoryGateStateStore()
    private val mandateStore = InMemoryMandateStore()
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z")

    private fun kotlinx.coroutines.test.TestScope.repository(): GateCheckRepository {
        val moderation = ModerationRepository(
            backend = backend,
            attestation = attestation,
            signer = signer,
            mandateStore = mandateStore,
            clock = { now },
        )
        return GateCheckRepository(
            attestation = attestation,
            backend = backend,
            moderation = moderation,
            signer = signer,
            store = gateStore,
            scope = this,
            clock = { now },
        )
    }

    private suspend fun consent() {
        ModerationRepository(
            backend = backend,
            attestation = attestation,
            signer = signer,
            mandateStore = mandateStore,
            clock = { now },
        ).consent(ModerationFixtures.listing(), ModerationFixtures.reviewedManifest())
    }

    @Test
    fun `without a mandate the gate is notMandated and no request is sent`() = runTest {
        val repository = repository()
        repository.checkNow()
        assertEquals(GateStatus.NotMandated, repository.snapshots.value)
        assertEquals(null, backend.lastGateRequest)
    }

    @Test
    fun `a banned answer blocks and persists`() = runTest {
        consent()
        val ban = BanState(verdictRef = "v1", authorityContact = "appeals@a.org")
        backend.gateResults.addLast(GateCheckResult.Banned(ban))

        val repository = repository()
        repository.checkNow()

        assertEquals(GateStatus.Banned(ban), repository.snapshots.value)
        assertEquals(GateCheckResult.Banned(ban), gateStore.load()?.lastResult)

        // The request carried the mandate ref, the challenge, the
        // token, and a signature over the reconstructed payload.
        val request = backend.lastGateRequest!!
        assertEquals("onym:key:aabb", request.userKey)
        assertEquals(64, request.mandateRef!!.length)
        assertEquals("fixture-integrity-token", request.integrityToken)
        assertEquals(
            SignedSessionPayload.requestHash(signer.signedPayloads.last()),
            attestation.requestHashes.last(),
        )
    }

    @Test
    fun `no_mandate routes to enrollmentLost`() = runTest {
        consent()
        backend.failWith(400, "no_mandate")
        val repository = repository()
        repository.checkNow()
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
            repository.snapshots.value,
        )
    }

    @Test
    fun `a 403 routes to backendRefused and discards no persisted state`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()
        assertEquals(GateStatus.Operational(), repository.snapshots.value)

        backend.failWith(403, null)
        now = now.plusSeconds(3600)
        repository.checkNow()
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.BACKEND_REFUSED),
            repository.snapshots.value,
        )
        assertEquals(GateCheckResult.Clear, gateStore.load()?.lastResult)
    }

    /** A 404/400 deploy regression must fall to grace, not hard-block. */
    @Test
    fun `an unrecognized 4xx serves grace`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()

        backend.failWith(404, "not_found")
        now = now.plusSeconds(3600)
        repository.checkNow()
        assertEquals(GateStatus.Operational(), repository.snapshots.value)
    }

    @Test
    fun `unreachable past grace blocks`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()

        backend.failUnreachable()
        now = now.plusSeconds(4 * 86_400)
        repository.checkNow()
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.OFFLINE_GRACE_EXPIRED),
            repository.snapshots.value,
        )
    }

    @Test
    fun `a throttled provider sends nothing and serves grace`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()
        val requestsAfterFirst = backend.lastGateRequest

        attestation.answer = AttestationToken.Throttled
        now = now.plusSeconds(3600)
        repository.checkNow()
        assertEquals(GateStatus.Operational(), repository.snapshots.value)
        // No new request reached the backend.
        assertEquals(requestsAfterFirst, backend.lastGateRequest)
    }

    @Test
    fun `an unsupported environment sends a token-less request`() = runTest {
        consent()
        attestation.answer = AttestationToken.Unsupported
        backend.gateResults.addLast(
            GateCheckResult.CheckRequired(CheckRequiredReason.ATTESTATION_UNAVAILABLE),
        )
        val repository = repository()
        repository.checkNow()
        assertEquals(null, backend.lastGateRequest!!.integrityToken)
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ATTESTATION_UNAVAILABLE),
            repository.snapshots.value,
        )
    }

    /** Same-second checks must not produce byte-identical timestamps. */
    @Test
    fun `same-second sessions bump the timestamp`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()
        val first = backend.lastGateRequest!!.timestamp
        // Forced: the same-second collision is exactly what the
        // min-recheck guard would otherwise skip.
        repository.checkNow(force = true)
        val second = backend.lastGateRequest!!.timestamp
        assertTrue("$first vs $second", second > first)
    }

    /** Concurrent triggers (cold start's launch check + onStart's
     * foreground check, or foreground thrash) must share one flight:
     * every extra check spends a challenge and a Play Integrity
     * standard request against quota. */
    @Test
    fun `concurrent checks coalesce into one flight`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val challengesAfterConsent = backend.challengeCounter
        val holdTheWire = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.gateDelay = holdTheWire

        val repository = repository()
        val first = launch { repository.checkNow() }
        val second = launch { repository.checkNow() }
        testScheduler.runCurrent()
        // Both callers are waiting; only one check reached the wire.
        assertEquals(challengesAfterConsent + 1, backend.challengeCounter)

        holdTheWire.complete(Unit)
        first.join()
        second.join()
        assertEquals(challengesAfterConsent + 1, backend.challengeCounter)
        assertEquals(GateStatus.Operational(), repository.snapshots.value)

        // A call AFTER the flight landed (and past the min-recheck
        // window) is a fresh check.
        backend.gateDelay = null
        now = now.plusSeconds(61)
        repository.checkNow()
        assertEquals(challengesAfterConsent + 2, backend.challengeCounter)
    }

    /**
     * The post-consent path must not adopt a pre-consent answer: a
     * flight already on the wire (stale `no_mandate` coming) is
     * outrun by a forced check, and the stale result — landing later
     * — is discarded by the generation guard.
     */
    @Test
    fun `a forced check starts fresh and the stale flight is discarded`() = runTest {
        consent()
        backend.failWith(400, "no_mandate")
        val holdTheWire = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.gateDelay = holdTheWire

        val repository = repository()
        val staleFlight = launch { repository.checkNow() }
        testScheduler.runCurrent() // the stale check is on the wire

        // Consent just landed (re-arm the backend as the fresh
        // enrollment would); the forced check must not join the
        // stale flight.
        backend.gateDelay = null
        backend.gateFailure = null
        backend.gateResults.addLast(GateCheckResult.Clear)
        repository.checkNow(force = true)
        assertEquals(GateStatus.Operational(), repository.snapshots.value)

        // The stale flight resumes into the pre-consent refusal and
        // lands it AFTER the forced check — the generation guard must
        // discard it, or the user bounces back to the consent surface
        // they just completed.
        backend.failWith(400, "no_mandate")
        holdTheWire.complete(Unit)
        staleFlight.join()
        assertEquals(GateStatus.Operational(), repository.snapshots.value)
    }

    /** After an identity switch the gate answers NotMandated (the
     * consent path) — it must never send the old identity's userKey
     * under the new identity's signature, which is a sticky
     * backendRefused no retry can clear. */
    @Test
    fun `an identity switch routes to consent not a mismatched session`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()
        assertEquals(GateStatus.Operational(), repository.snapshots.value)
        val requestsBefore = backend.challengeCounter

        signer.userKey = "onym:key:ccdd"
        now = now.plusSeconds(61)
        repository.checkNow()
        assertEquals(GateStatus.NotMandated, repository.snapshots.value)
        assertEquals("no session is sent for an unmandated identity", requestsBefore, backend.challengeCounter)
    }

    /** Serial passive triggers inside the min-recheck window skip the
     * network (coalescing only shares CONCURRENT flights; foreground
     * cycles are serial); an explicit force always runs. */
    @Test
    fun `serial checks inside the min-recheck window spend no quota`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val repository = repository()
        repository.checkNow()
        val afterFirst = backend.challengeCounter

        now = now.plusSeconds(10)
        repository.checkNow()
        assertEquals("a serial re-check 10s later is served from state", afterFirst, backend.challengeCounter)

        repository.checkNow(force = true)
        assertEquals("an explicit retry always runs", afterFirst + 1, backend.challengeCounter)

        now = now.plusSeconds(61)
        repository.checkNow()
        assertEquals("past the window the passive trigger runs", afterFirst + 2, backend.challengeCounter)
    }

    /**
     * The cleared-mandate laundering hole: with no mandate record the
     * gate used to answer NotMandated unconditionally, so Settings →
     * Clear data (or a corrupt store, which reads as empty) walked
     * around the persisted ban and the sticky refusal. Platform-state-
     * first: the persisted gate state keeps blocking.
     */
    @Test
    fun `a missing mandate does not discard a persisted ban or refusal`() = runTest {
        val ban = BanState(verdictRef = "v1", authorityContact = "appeals@a.org")
        gateStore.save(
            PersistedGateState(
                lastResult = GateCheckResult.Banned(ban),
                lastSuccessAt = "2026-08-08T11:00:00Z",
            ),
        )
        // No consent: the mandate store is empty.
        val repository = repository()
        repository.checkNow()
        assertEquals(GateStatus.Banned(ban), repository.snapshots.value)

        // Same for a sticky refusal.
        gateStore.save(
            PersistedGateState(
                lastResult = GateCheckResult.Clear,
                lastSuccessAt = "2026-08-08T11:00:00Z",
                refusalReason = CheckRequiredReason.BACKEND_REFUSED,
                refusedAt = "2026-08-08T11:30:00Z",
            ),
        )
        now = now.plusSeconds(61)
        repository.checkNow()
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.BACKEND_REFUSED),
            repository.snapshots.value,
        )

        // A genuinely clean history answers NotMandated -> consent.
        gateStore.save(null)
        now = now.plusSeconds(61)
        repository.checkNow()
        assertEquals(GateStatus.NotMandated, repository.snapshots.value)
    }

    /** A failing gate-state store must degrade toward blocking, not
     * escape runCheck as an uncaught crash in a SupervisorJob child —
     * and not leave the previous state standing (unmoderated
     * operation on a store fault). */
    @Test
    fun `a store failure degrades to neverChecked instead of crashing`() = runTest {
        consent()
        backend.gateResults.addLast(GateCheckResult.Clear)
        val throwingStore = object : GateStateStore {
            override suspend fun load(): PersistedGateState? =
                throw RuntimeException("disk full")
            override suspend fun save(state: PersistedGateState?) =
                throw RuntimeException("disk full")
        }
        val moderation = ModerationRepository(
            backend = backend,
            attestation = attestation,
            signer = signer,
            mandateStore = mandateStore,
            clock = { now },
        )
        val repository = GateCheckRepository(
            attestation = attestation,
            backend = backend,
            moderation = moderation,
            signer = signer,
            store = throwingStore,
            scope = this,
            clock = { now },
        )
        repository.checkNow()
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.NEVER_CHECKED),
            repository.snapshots.value,
        )
    }

    /** A 200 body smuggling `enrollmentLost` is normalized onto the
     * refusal path — it must not persist as a successful check. */
    @Test
    fun `enrollmentLost in a success body is normalized to a refusal`() = runTest {
        consent()
        backend.gateResults.addLast(
            GateCheckResult.CheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
        )
        val repository = repository()
        repository.checkNow()
        assertEquals(
            GateStatus.GateCheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
            repository.snapshots.value,
        )
        assertEquals(null, gateStore.load())
    }
}
