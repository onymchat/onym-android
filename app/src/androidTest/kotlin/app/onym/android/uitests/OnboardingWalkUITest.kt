package app.onym.android.uitests

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.OnymApplication
import app.onym.android.UITestRegistry
import app.onym.android.discovery.DiscoverySource
import app.onym.android.discovery.DiscoverySourcesConfiguration
import app.onym.android.onboarding.OnboardingStep
import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import app.onym.android.support.InMemoryOnboardingStore
import app.onym.android.uitests.screens.OnboardingScreenObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage of the first-launch onboarding walk (PR 4 of
 * the onboarding sequence):
 *
 *  1. Fresh state with the gate forced ON walks all 6 steps —
 *     welcome → discoveryConfirm (TOFU with the fixture fingerprint)
 *     → messageTransport → blobTransport → notary (legacy published
 *     add) → done — and lands in the tab shell; a dependency rebuild
 *     + Activity recreation then asserts the walk does NOT reappear
 *     (completion flag persisted, gate re-resolves false).
 *  2. The skip path: every skippable step skipped straight through.
 *  3. Back navigation revisits the prior step without restarting.
 *  4. The pre-bootstrap loading state on discoveryConfirm when no
 *     default source has hydrated.
 *  5. A completed-at-boot flag bypasses the walk entirely.
 *  6. The explicit harness contract: with NO onboarding slot
 *     registered, the gate resolves in UI-test mode and every
 *     pre-onboarding instrumented test boots straight to the tabs —
 *     the bypass the rest of the suite relies on.
 *
 * Determinism (the iOS #252 lessons, applied in-process):
 *  - the gate reads an [InMemoryOnboardingStore] and pins the
 *    grandfathering probe to "fresh user", so DataStore state left
 *    on the emulator by earlier runs can never flip the decision;
 *  - [UITestRegistry.discoveryClock] injects a fixture-era instant
 *    (the signed fixtures expire — `snapshot-1.json` on 2026-09-12 —
 *    and these tests must not rot with them).
 *
 * Coverage caveat: grandfathering ("existing user → no walk") has NO
 * instrumented coverage through the real probe wiring — with the
 * slot set the probe is pinned to "fresh user" by design — so that
 * truth table lives exclusively in the unit tests
 * (`OnboardingGateProbeTest` / `OnboardingGateTest`).
 *
 * Network is replayed from the shared Discovery conformance fixtures
 * (see [DiscoverySettingsUITest] for the byte-exactness contract).
 * Registry discipline follows [DiscoverySettingsUITest]: an
 * `order = 0` rule populates [UITestRegistry] (branching on the
 * per-test annotations below) BEFORE the compose rule launches
 * [MainActivity].
 */
@RunWith(AndroidJUnit4::class)
class OnboardingWalkUITest {

    /** Boot WITHOUT the onboarding slot: the pre-PR-4 harness state,
     *  now the explicit bypass contract. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class NoOnboardingSlot

    /** Boot with the completion flag already set. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class AlreadyCompleted

    /** Don't seed the unpinned default source — the discoveryConfirm
     *  step must render its loading state, not a dead-end hero. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class EmptyDiscoverySources

    private val onboardingStore = InMemoryOnboardingStore()

    private val discoveryStore = InMemoryDiscoveryStore()
    private val discoveryFetcher = FakeDiscoveryFetcher().apply {
        respond(MANIFEST_URL, fixture("provider-manifest.json"))
        respond(SNAPSHOT_URL, fixture("snapshot-1.json"))
        respond(ENTRY_MANIFEST_URL, fixture("destination-manifest.json"))
    }

    // Non-discovery seams, seeded exactly like DiscoverySettingsUITest
    // so the rest of boot stays offline.
    private val relayerStore = app.onym.android.support.InMemoryRelayerSelectionStore()
    private val relayerFetcher = app.onym.android.support.FakeKnownRelayersFetcher(
        app.onym.android.support.FakeKnownRelayersFetcher.Mode.Succeeds(
            listOf(
                app.onym.android.chain.RelayerEndpoint(
                    "Onym Testnet",
                    RELAYER_URL,
                    listOf("testnet"),
                ),
            ),
        ),
    )
    private val contractsStore = app.onym.android.support.InMemoryAnchorSelectionStore()
    private val contractsFetcher = app.onym.android.support.FakeContractsManifestFetcher(
        app.onym.android.support.FakeContractsManifestFetcher.Mode.Succeeds(
            app.onym.android.chain.ContractsManifest(version = 1, releases = emptyList()),
        ),
    )

    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            UITestRegistry.relayerStore = relayerStore
            UITestRegistry.relayerFetcher = relayerFetcher
            UITestRegistry.contractsStore = contractsStore
            UITestRegistry.contractsFetcher = contractsFetcher
            UITestRegistry.discoveryFetcher = discoveryFetcher
            UITestRegistry.discoveryStore = discoveryStore
            UITestRegistry.discoveryClock = { FIXTURE_ERA }
            if (description.getAnnotation(EmptyDiscoverySources::class.java) == null) {
                // The unpinned default source the discoveryConfirm
                // hero reviews — the production seed is skipped under
                // the harness, so the walk test provides its own.
                runBlocking {
                    discoveryStore.saveConfiguration(
                        DiscoverySourcesConfiguration(
                            sources = listOf(
                                DiscoverySource(
                                    url = MANIFEST_URL,
                                    label = "Onym Discovery",
                                    providerId = PROVIDER_ID,
                                    pinnedOperatorKeyHex = null,
                                ),
                            ),
                        ),
                    )
                }
            }
            if (description.getAnnotation(NoOnboardingSlot::class.java) == null) {
                if (description.getAnnotation(AlreadyCompleted::class.java) != null) {
                    runBlocking { onboardingStore.markCompleted() }
                }
                UITestRegistry.onboardingStore = onboardingStore
            }
            UITestRegistry.enabled = true
            val app = ApplicationProvider.getApplicationContext<OnymApplication>()
            app.rebuildDependenciesForTest()
        }

        override fun finished(description: Description) {
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ─── (1) the full walk ────────────────────────────────────────

    @Test
    @Ignore(
        "Redesigned flow (welcome/identity/services/recoveryPhrase/done) — " +
            "the new end-to-end walk (identity outcome gate, services hub, " +
            "recovery reveal via the biometric fake) lands in the tests PR.",
    )
    fun walkAllSteps_completesToTabShell_andDoesNotReappear() {
        val onboarding = OnboardingScreenObject(composeRule)

        // welcome → the only path forward is Continue (no Skip).
        onboarding.awaitStep(OnboardingStep.Welcome)
        onboarding.tapPrimary(OnboardingStep.Welcome)

        // identity: Continue unlocks once the bootstrap yields a
        // snapshot and the step content records its outcome.
        onboarding.awaitStep(OnboardingStep.Identity)
        onboarding.tapPrimary(OnboardingStep.Identity)

        // services: the recommended setup is preselected; Continue
        // accepts it.
        onboarding.awaitStep(OnboardingStep.Services)
        onboarding.tapPrimary(OnboardingStep.Services)

        // recoveryPhrase: "Remind me later" is the deferral path.
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.tapSkip(OnboardingStep.RecoveryPhrase)

        // done → Start messaging completes the walk.
        onboarding.awaitStep(OnboardingStep.Done)
        onboarding.tapPrimary(OnboardingStep.Done)
        onboarding.awaitTabShell()
        assertTrue("completion flag must be persisted", onboardingStore.completed)
        assertEquals("flag written exactly once", 1, onboardingStore.markCount)

        // Re-resolution: rebuild the dependency graph (fresh gate
        // decision off the same store) and recreate the Activity —
        // onboarding must NOT reappear.
        val app = ApplicationProvider.getApplicationContext<OnymApplication>()
        app.rebuildDependenciesForTest()
        composeRule.activityRule.scenario.recreate()
        onboarding.awaitTabShell()
        onboarding.assertNotShown()
        assertEquals("re-resolution must not re-write the flag", 1, onboardingStore.markCount)
    }

    // ─── (2) the skip path ────────────────────────────────────────

    @Test
    @Ignore(
        "Redesigned flow — only recoveryPhrase is skippable now; the " +
            "new skip-path walk lands in the tests PR.",
    )
    fun skipPath_everySkippableStep_stillCompletes() {
        val onboarding = OnboardingScreenObject(composeRule)

        onboarding.awaitStep(OnboardingStep.Welcome)
        onboarding.tapPrimary(OnboardingStep.Welcome)
        onboarding.awaitStep(OnboardingStep.Identity)
        onboarding.tapPrimary(OnboardingStep.Identity)
        onboarding.awaitStep(OnboardingStep.Services)
        onboarding.tapPrimary(OnboardingStep.Services)
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.tapSkip(OnboardingStep.RecoveryPhrase)

        onboarding.awaitStep(OnboardingStep.Done)
        onboarding.tapPrimary(OnboardingStep.Done)
        onboarding.awaitTabShell()
        assertTrue(onboardingStore.completed)
        // Skipping trusted nothing: the seeded source stays unpinned.
        assertTrue(
            discoveryStore.loadConfigurationBlocking().sources
                .all { it.pinnedOperatorKeyHex == null },
        )
    }

    // ─── (3) back navigation ──────────────────────────────────────

    @Test
    fun backNavigation_revisitsPriorStep_withoutRestartingTheWalk() {
        val onboarding = OnboardingScreenObject(composeRule)

        onboarding.awaitStep(OnboardingStep.Welcome)
        onboarding.tapPrimary(OnboardingStep.Welcome)
        onboarding.awaitStep(OnboardingStep.Identity)

        onboarding.tapBack(OnboardingStep.Identity)
        onboarding.awaitStep(OnboardingStep.Welcome)

        onboarding.tapPrimary(OnboardingStep.Welcome)
        onboarding.awaitStep(OnboardingStep.Identity)
        assertFalse("the walk must not have completed", onboardingStore.completed)
    }

    // (4) The old pre-bootstrap directory-loading test is gone: the
    // directory surface moved into the services hub, and the
    // redesigned hub walks live in the follow-up tests PR. The
    // @EmptyDiscoverySources seam stays for those walks.

    // ─── (5) completed flag at boot ───────────────────────────────

    @Test
    @AlreadyCompleted
    fun completedFlag_atBoot_bypassesTheWalk() {
        val onboarding = OnboardingScreenObject(composeRule)
        onboarding.awaitTabShell()
        onboarding.assertNotShown()
    }

    // ─── (6) explicit harness bypass contract ─────────────────────

    @Test
    @NoOnboardingSlot
    fun noOnboardingSlot_harnessBootsStraightToTabs() {
        // The contract every pre-onboarding instrumented test relies
        // on, asserted rather than incidental: registry enabled, no
        // onboarding slot → UI-test-mode gate → tabs.
        val onboarding = OnboardingScreenObject(composeRule)
        onboarding.awaitTabShell()
        onboarding.assertNotShown()
    }

    // ─── fixtures ─────────────────────────────────────────────────

    private companion object {
        const val MANIFEST_URL = "https://discovery.onym.app/manifest.json"
        const val SNAPSHOT_URL = "https://discovery.onym.app/catalogs/public-all-seats.json"
        const val ENTRY_MANIFEST_URL = "https://discovery.onym.app/manifests/onym-courier.json"
        const val PROVIDER_ID = "onym:component:onym-discovery"
        const val OPERATOR_KEY_HEX =
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c"
        const val RELAYER_URL = "https://relayer-testnet.onym.chat"

        /** Inside the fixtures' validity window (snapshot expires
         *  2026-09-12) — frozen so the suite can't rot. */
        val FIXTURE_ERA: Instant = Instant.parse("2026-08-14T00:00:00Z")

        fun fixture(name: String): ByteArray =
            checkNotNull(
                OnboardingWalkUITest::class.java.classLoader
                    ?.getResourceAsStream("fixtures/$name"),
            ) { "missing androidTest fixture: fixtures/$name" }
                .use { it.readBytes() }
    }
}

/** Main-thread-safe suspend shorthand for assertions — same pattern
 *  as [DiscoverySettingsUITest]'s store helpers. */
private fun InMemoryDiscoveryStore.loadConfigurationBlocking(): DiscoverySourcesConfiguration =
    runBlocking { loadConfiguration() }
