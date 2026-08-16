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
        repository.checkNow()
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

        // A call AFTER the flight landed is a fresh check.
        backend.gateDelay = null
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
