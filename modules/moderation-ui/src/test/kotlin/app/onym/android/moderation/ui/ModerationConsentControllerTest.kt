package app.onym.android.moderation.ui

import app.onym.android.moderation.BackendUnreachableException
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.support.FakeAuthorityManifestFetcher
import app.onym.android.moderation.support.FakeAuthorityClient
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

    private val mandateStore = InMemoryMandateStore()
    private val attestation = FakeDeviceAttestationProvider()
    private val authority = FakeAuthorityClient()
    private val moderation = ModerationRepository(
        backend = backend,
        attestation = attestation,
        signer = FakeModerationSigner(),
        mandateStore = mandateStore,
        authority = authority,
        clock = { Instant.parse("2026-08-08T12:00:00Z") },
    )

    private fun controller(
        resumeExistingMandate: Boolean = true,
    ): ModerationConsentController = ModerationConsentController(
        authoritiesFetcher = FakeKnownAuthoritiesFetcher(),
        manifestFetcher = FakeAuthorityManifestFetcher(),
        moderation = moderation,
        resumeExistingMandate = resumeExistingMandate,
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
     * A DETERMINISTIC refusal is not unavailability: routing it to
     * Unavailable would hand its Continue to exactly the caller a
     * production backend refuses (a de-Googled device's token-less
     * enrollment, a blocked host presenting garbage) — a mandatory
     * step bypassed into unmoderated operation. However many times it
     * repeats, the surface stays Review + retry with the backend's
     * reason.
     */
    @Test
    fun `a refusing backend never routes to the deferral state`() = runTest {
        backend.enrollFailure = app.onym.android.moderation.BackendRejectedException(
            statusCode = 400,
            rawCode = "signature_invalid",
            message = "Google did not validate this integrity token",
        )
        val controller = controller()
        controller.load()
        repeat(4) {
            assertEquals(null, controller.agree())
            val state = controller.snapshots.value
            assertTrue(
                "$state",
                state is ModerationConsentController.UiState.Review && state.error != null,
            )
        }
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

    /**
     * The Huawei-class product decision: GMS-less hardware whose
     * token-less enrollment a production backend refuses routes to
     * the deferrable Unavailable state on the FIRST attempt —
     * retrying cannot change the hardware, and blocking onboarding
     * there would exclude those users from the protocol entirely
     * rather than from moderated enforcement (§8.5 reach limitation).
     */
    @Test
    fun `an unsupported device defers immediately`() = runTest {
        attestation.answer = app.onym.android.moderation.AttestationToken.Unsupported
        backend.enrollFailure = app.onym.android.moderation.BackendRejectedException(
            400,
            null,
            "integrityToken is required when Play Integrity is configured",
        )
        val controller = controller()
        controller.load()
        assertEquals(null, controller.agree())
        assertTrue(
            "${controller.snapshots.value}",
            controller.snapshots.value is ModerationConsentController.UiState.Unavailable,
        )
    }

    /**
     * The rotation double-enrollment defect: a NonCancellable agree()
     * persists the mandate, the rotation kills the surface before its
     * follow-up, and the RECREATED controller reloaded to Review —
     * a second live Agree, a second backend enrollment. A fresh
     * first-consent controller re-emits Consented from the persisted
     * record instead.
     */
    @Test
    fun `a recreated controller resumes a persisted consent`() = runTest {
        val first = controller()
        first.load()
        assertNotNull(first.agree())

        // Rotation: a brand-new controller over the same repository.
        val recreated = controller()
        recreated.load()
        assertTrue(
            "${recreated.snapshots.value}",
            recreated.snapshots.value is ModerationConsentController.UiState.Consented,
        )
    }

    /** The enrollment-lost re-consent host must NOT resume: a local
     * record exists there by definition, but the backend lost the
     * enrollment and only a fresh transaction repairs it — resuming
     * would pin that surface on "consent recorded" forever. */
    @Test
    fun `a re-consent controller reviews despite the existing record`() = runTest {
        val first = controller()
        first.load()
        assertNotNull(first.agree())

        val reconsent = controller(resumeExistingMandate = false)
        reconsent.load()
        assertTrue(
            "${reconsent.snapshots.value}",
            reconsent.snapshots.value is ModerationConsentController.UiState.Review,
        )
        // And agreeing again runs a fresh transaction.
        assertNotNull(reconsent.agree())
    }

    /** Registration is delivery, not consent: an unreachable authority
     * must not fail the consent surface — the artifact is persisted
     * and delivery retries on a later session ([ModerationRepository.
     * registerPending]). */
    @Test
    fun `an unreachable authority still consents`() = runTest {
        authority.failure =
            app.onym.android.moderation.AuthorityUnreachableException("authority down")
        val controller = controller()
        controller.load()
        assertNotNull(controller.agree())
        assertTrue(
            "${controller.snapshots.value}",
            controller.snapshots.value is ModerationConsentController.UiState.Consented,
        )
    }

    /** A deterministic authority rejection is a judged "no" (the
     * repository only lets those escape): like the backend-refusal
     * case above, it must never escalate to the deferrable
     * Unavailable — Review + the reason, retry only. */
    @Test
    fun `a refusing authority never routes to the deferral state`() = runTest {
        authority.failure = app.onym.android.moderation.AuthorityRejectedException(
            statusCode = 400,
            rawCode = "bad_request",
            message = "mandate pins a different manifest than the one this authority publishes",
        )
        val controller = controller()
        controller.load()
        repeat(4) {
            assertEquals(null, controller.agree())
            val state = controller.snapshots.value
            assertTrue(
                "$state",
                state is ModerationConsentController.UiState.Review && state.error != null,
            )
        }
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
