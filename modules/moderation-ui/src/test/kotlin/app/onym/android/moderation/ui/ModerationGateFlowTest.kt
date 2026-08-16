package app.onym.android.moderation.ui

import app.onym.android.moderation.CheckRequiredReason
import app.onym.android.moderation.GateCheckRepository
import app.onym.android.moderation.GateStatus
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryGateStateStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import java.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModerationGateFlowTest {
    private val backend = ScriptedEnforcementBackendClient()
    private val attestation = FakeDeviceAttestationProvider()
    private val signer = FakeModerationSigner()
    private val mandateStore = InMemoryMandateStore()
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z")

    /** Scripted directory answer, flippable mid-test. */
    private var directoryAvailable = false

    private fun kotlinx.coroutines.test.TestScope.harness(): Pair<ModerationGateFlow, GateCheckRepository> {
        val moderation = ModerationRepository(
            backend = backend,
            attestation = attestation,
            signer = signer,
            mandateStore = mandateStore,
            clock = { now },
        )
        val gate = GateCheckRepository(
            attestation = attestation,
            backend = backend,
            moderation = moderation,
            signer = signer,
            store = InMemoryGateStateStore(),
            scope = this,
            clock = { now },
        )
        val flow = ModerationGateFlow(
            gate = gate,
            authoritiesAvailable = { directoryAvailable },
            scope = this,
        )
        return flow to gate
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

    /**
     * The buttonless-brick regression: the directory probe failing at
     * launch must not pin `ENROLLMENT_LOST` on the blocking branch for
     * the process lifetime — a retry re-probes, and a now-reachable
     * directory flips the gate to re-consent. (`GateStatus` is a
     * value, so an identical re-derivation would not re-emit through
     * the collect — the retry path carries the refresh itself.)
     */
    @Test
    fun `enrollment lost recovers to re-consent once the directory answers`() = runTest {
        consent()
        backend.failWith(400, "no_mandate")
        directoryAvailable = false

        val (flow, gate) = harness()
        flow.start()
        gate.checkNow()
        advanceUntilIdle()
        assertEquals(
            RootGate.CheckRequired(CheckRequiredReason.ENROLLMENT_LOST),
            flow.snapshots.value,
        )

        // The launch-time blip clears; the user taps Retry.
        directoryAvailable = true
        flow.tappedRetry()
        advanceUntilIdle()
        assertEquals(RootGate.NeedsConsent, flow.snapshots.value)
        flow.stop()
    }

    /** The empty-directory softening: no mandate + nothing to consent
     * to renders the app. */
    @Test
    fun `no mandate with an empty directory is operational`() = runTest {
        directoryAvailable = false
        val (flow, gate) = harness()
        flow.start()
        gate.checkNow()
        advanceUntilIdle()
        assertEquals(RootGate.Operational(), flow.snapshots.value)
        // The gate itself still knows the truth.
        assertEquals(GateStatus.NotMandated, gate.snapshots.value)
        flow.stop()
    }

    @Test
    fun `no mandate with an available directory needs consent`() = runTest {
        directoryAvailable = true
        val (flow, gate) = harness()
        flow.start()
        gate.checkNow()
        advanceUntilIdle()
        assertEquals(RootGate.NeedsConsent, flow.snapshots.value)
        flow.stop()
    }

    /**
     * The unreachable-authority softening: a previously-unmandated
     * user facing a failing manifest fetch may defer consent for this
     * process instead of sitting on a back-blocked Unavailable screen
     * for the outage's duration.
     */
    @Test
    fun `deferring consent softens NeedsConsent to operational`() = runTest {
        directoryAvailable = true
        val (flow, gate) = harness()
        flow.start()
        gate.checkNow()
        advanceUntilIdle()
        assertEquals(RootGate.NeedsConsent, flow.snapshots.value)

        flow.deferConsent()
        assertEquals(RootGate.Operational(), flow.snapshots.value)
        flow.stop()
    }
}
