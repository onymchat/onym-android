package app.onym.android.moderation.ui

import app.onym.android.moderation.BackendUnreachableException
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.support.FakeAuthorityManifestFetcher
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeKnownAuthoritiesFetcher
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationConsentControllerTest {
    private val backend = ScriptedEnforcementBackendClient()

    private fun controller(): ModerationConsentController = ModerationConsentController(
        authoritiesFetcher = FakeKnownAuthoritiesFetcher(),
        manifestFetcher = FakeAuthorityManifestFetcher(),
        moderation = ModerationRepository(
            backend = backend,
            attestation = FakeDeviceAttestationProvider(),
            signer = FakeModerationSigner(),
            mandateStore = InMemoryMandateStore(),
            clock = { Instant.parse("2026-08-08T12:00:00Z") },
        ),
    )

    @Test
    fun `the happy path reviews and consents`() = runTest {
        val controller = controller()
        controller.load()
        assertTrue(controller.snapshots.value is ModerationConsentController.UiState.Review)
        assertNotNull(controller.agree())
        assertTrue(controller.snapshots.value is ModerationConsentController.UiState.Consented)
    }

    /**
     * The unpassable-wizard defect: the moderation step is unskippable
     * and outcome-gated, and only the Unavailable state offers a way
     * forward (Continue → Unavailable outcome / deferral). A down or
     * refusing enforcement backend — or a de-Googled device whose
     * token-less enrollment a production backend refuses — must
     * therefore route there after repeated failure, never loop the
     * user on an error string with only Agree.
     */
    @Test
    fun `repeated consent failure routes to Unavailable`() = runTest {
        backend.enrollFailure = BackendUnreachableException("backend down")
        val controller = controller()
        controller.load()

        // First failure: a blip — keep the Review surface, retry in
        // place, with the reason shown.
        assertEquals(null, controller.agree())
        val afterFirst = controller.snapshots.value
        assertTrue(
            "$afterFirst",
            afterFirst is ModerationConsentController.UiState.Review && afterFirst.error != null,
        )

        // Second consecutive failure: the way forward.
        assertEquals(null, controller.agree())
        assertTrue(
            "${controller.snapshots.value}",
            controller.snapshots.value is ModerationConsentController.UiState.Unavailable,
        )
    }

    /**
     * The orphaned-enrollment defect: `agree` runs on the surface's
     * composition scope, and the surface can leave composition
     * mid-transaction (a gate flip). Cancellation between enroll and
     * the mandate save stranded a backend enrollment and pinned the
     * controller in Consenting. The transaction is NonCancellable:
     * it completes, the record persists, the state reaches Consented.
     */
    @Test
    fun `cancelling the surface mid-agree still completes the consent`() = runTest {
        val holdEnroll = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.enrollGate = holdEnroll
        val controller = controller()
        controller.load()

        val surfaceScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + testScheduler.let {
                kotlinx.coroutines.test.StandardTestDispatcher(it)
            },
        )
        val agreeing = surfaceScope.launch { controller.agree() }
        testScheduler.runCurrent() // suspended inside enrollDevice

        // The surface leaves composition: its scope is cancelled.
        surfaceScope.cancel()
        holdEnroll.complete(Unit)
        agreeing.join()

        assertTrue(
            "${controller.snapshots.value}",
            controller.snapshots.value is ModerationConsentController.UiState.Consented,
        )
    }

    /** Retry (reload) resets the failure count — a recovered backend
     * gets a fresh Review, not an instant Unavailable. */
    @Test
    fun `reload resets the failure count and a recovered backend consents`() = runTest {
        backend.enrollFailure = BackendUnreachableException("backend down")
        val controller = controller()
        controller.load()
        controller.agree()
        controller.agree()
        assertTrue(
            controller.snapshots.value is ModerationConsentController.UiState.Unavailable,
        )

        backend.enrollFailure = null
        controller.load()
        assertTrue(controller.snapshots.value is ModerationConsentController.UiState.Review)
        assertNotNull(controller.agree())
        assertTrue(controller.snapshots.value is ModerationConsentController.UiState.Consented)
    }
}
