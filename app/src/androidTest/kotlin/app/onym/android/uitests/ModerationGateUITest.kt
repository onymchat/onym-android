package app.onym.android.uitests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.OnymApplication
import app.onym.android.UITestRegistry
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.moderation.BanState
import app.onym.android.moderation.CheckRequiredReason
import app.onym.android.moderation.GateCheckResult
import app.onym.android.moderation.support.FakeAuthorityClient
import app.onym.android.moderation.support.FakeAuthorityManifestFetcher
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeKnownAuthoritiesFetcher
import app.onym.android.moderation.support.InMemoryGateStateStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import app.onym.android.moderation.keyReference
import app.onym.android.moderation.support.fixtureMandateRecord
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.security.Security

/**
 * Full-screen moderation gate walk-throughs against registry fakes
 * (the seat's `debugActive` seams: scripted enforcement backend,
 * fixed-token attestation), mirroring the iOS `--moderation-scenario`
 * UI tests:
 *
 *  1. [aBannedAnswerReplacesTheShell] — a scripted `banned` gate
 *     result renders BannedScreen (with the appeal route) instead of
 *     the tab shell.
 *  2. [aCheckRequiredAnswerBlocksWithRetry] — `checkRequired` blocks;
 *     Retry re-checks against a backend that now answers `clear`, and
 *     the shell appears.
 *  3. [aLostEnrollmentRoutesToConsentAndReconsentsThrough] — the
 *     backend's `no_mandate` refusal lands on the consent surface;
 *     agreeing re-enrolls through the scripted backend and the shell
 *     appears.
 *
 * No onboarding slot is registered, so the walk is bypassed and the
 * gate composes directly against the shell (the post-onboarding
 * user's shape). A seeded active mandate record puts the gate past
 * NotMandated without walking consent.
 */
@RunWith(AndroidJUnit4::class)
class ModerationGateUITest {

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class Banned

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class CheckRequired

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class EnrollmentLost

    private val backend = ScriptedEnforcementBackendClient()
    private val attestation = FakeDeviceAttestationProvider()
    private val mandateStore = InMemoryMandateStore()
    private val gateStateStore = InMemoryGateStateStore()

    private lateinit var identityStore: IdentitySecretStore

    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
            val ctx = ApplicationProvider.getApplicationContext<OnymApplication>()
            identityStore = IdentitySecretStore(
                ctx,
                prefsFileName = "app.onym.android.identity.moderationgateuitests",
            ).also { it.wipeAll() }
            UITestRegistry.identitySecretStore = identityStore
            // Seed the non-moderation seams this test never asserts
            // on, so boot doesn't trip on missing relayer/contracts
            // wiring (the IdentitiesUITest pattern).
            UITestRegistry.relayerStore =
                app.onym.android.support.InMemoryRelayerSelectionStore().apply {
                    kotlinx.coroutines.runBlocking {
                        saveConfiguration(
                            app.onym.android.chain.RelayerConfiguration(
                                endpoints = listOf(
                                    app.onym.android.chain.RelayerEndpoint(
                                        "test",
                                        "https://relayer.test.invalid",
                                        listOf("testnet"),
                                    ),
                                ),
                                hasUserInteracted = true,
                            ),
                        )
                    }
                }
            UITestRegistry.relayerFetcher = app.onym.android.support.FakeKnownRelayersFetcher(
                app.onym.android.support.FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()),
            )
            UITestRegistry.contractsStore =
                app.onym.android.support.InMemoryAnchorSelectionStore()
            UITestRegistry.contractsFetcher =
                app.onym.android.support.FakeContractsManifestFetcher(
                    app.onym.android.support.FakeContractsManifestFetcher.Mode.Succeeds(
                        app.onym.android.chain.ContractsManifest(
                            version = 1,
                            releases = emptyList(),
                        ),
                    ),
                )

            when {
                description.getAnnotation(Banned::class.java) != null -> {
                    seedMandate()
                    backend.gateResults.addLast(
                        GateCheckResult.Banned(
                            BanState(
                                verdictRef = "fixture-verdict",
                                authorityContact = "appeals@test-authority.example",
                                appealUrl = "https://test-authority.example/appeal",
                            ),
                        ),
                    )
                }
                description.getAnnotation(CheckRequired::class.java) != null -> {
                    seedMandate()
                    // A single scripted entry repeats for every boot
                    // check (launch loop + foreground both fire); the
                    // test flips the script to `clear` before Retry.
                    backend.gateResults.addLast(
                        GateCheckResult.CheckRequired(CheckRequiredReason.TOKEN_INVALID),
                    )
                }
                description.getAnnotation(EnrollmentLost::class.java) != null -> {
                    seedMandate()
                    backend.failWith(400, "no_mandate", "no mandate on file")
                }
            }

            UITestRegistry.moderationBackend = backend
            UITestRegistry.moderationAuthorityClient = FakeAuthorityClient()
            UITestRegistry.moderationAttestation = attestation
            UITestRegistry.moderationMandateStore = mandateStore
            UITestRegistry.moderationGateStateStore = gateStateStore
            UITestRegistry.moderationAuthoritiesFetcher = FakeKnownAuthoritiesFetcher()
            UITestRegistry.moderationManifestFetcher = FakeAuthorityManifestFetcher()
            UITestRegistry.enabled = true
            ctx.rebuildDependenciesForTest()
        }

        override fun finished(description: Description) {
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Seed the mandate under the REAL device identity's key: the
     * repository resolves the active mandate per identity
     * (mandate.user must equal the current signer's key), so a
     * fixture key would resolve NotMandated and boot into the consent
     * surface instead of the scripted gate. Bootstrapping here is
     * idempotent — the app's own repository loads the same persisted
     * identity.
     */
    private fun seedMandate() {
        kotlinx.coroutines.runBlocking {
            val identity = IdentityRepository(identityStore).bootstrap()
            val userKey = keyReference(identity.stellarPublicKey)
            mandateStore.save(listOf(fixtureMandateRecord(userKey = userKey)))
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasTestTag(tag),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @Banned
    fun aBannedAnswerReplacesTheShell() {
        awaitTag("moderation.banned")
        composeRule.onNodeWithTag("moderation.banned", useUnmergedTree = true)
            .assertIsDisplayed()
        // The route out renders — a silent brick is nonconforming.
        composeRule.onNodeWithTag("moderation.banned.appeal", useUnmergedTree = true)
            .assertIsDisplayed()
        // The shell did not compose behind the gate.
        composeRule.onAllNodes(
            androidx.compose.ui.test.hasTestTag("nav.tab.chats"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().let { nodes ->
            org.junit.Assert.assertTrue(nodes.isEmpty())
        }
    }

    @Test
    @CheckRequired
    fun aCheckRequiredAnswerBlocksWithRetry() {
        awaitTag("moderation.gateRequired")
        // The backend recovers: from now on every check answers clear.
        backend.gateResults.clear()
        backend.gateResults.addLast(GateCheckResult.Clear)
        composeRule.onNodeWithTag("moderation.gateRequired.retry", useUnmergedTree = true)
            .performClick()
        // The retry's `clear` opens the shell.
        awaitTag("nav.tab.chats")
    }

    @Test
    @EnrollmentLost
    fun aLostEnrollmentRoutesToConsentAndReconsentsThrough() {
        awaitTag("moderation.consent")
        // Re-arm the backend: consent re-enrolls, and the post-consent
        // gate check must now answer clear.
        backend.gateFailure = null
        backend.gateResults.addLast(GateCheckResult.Clear)

        awaitTag("moderation.consent.agree")
        composeRule.onNodeWithTag("moderation.consent.agree", useUnmergedTree = true)
            // The structured terms cards put Agree below the fold;
            // scroll to it as a user would before tapping — a click
            // on an off-viewport node injects outside the window.
            .performScrollTo()
            .performClick()
        awaitTag("nav.tab.chats")
        assertNotNull("consent re-enrolled through the backend", backend.lastEnrollment)
    }
}
