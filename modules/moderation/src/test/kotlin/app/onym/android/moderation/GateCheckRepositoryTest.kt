package app.onym.android.moderation

import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryGateStateStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import java.time.Instant
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
