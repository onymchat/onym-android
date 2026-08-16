package app.onym.android.uitests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.OnymApplication
import app.onym.android.UITestRegistry
import app.onym.android.discovery.DiscoverySource
import app.onym.android.discovery.DiscoverySourcesConfiguration
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.onboarding.OnboardingStep
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.onym.android.moderation.support.FakeAuthorityClient
import app.onym.android.moderation.support.FakeAuthorityManifestFetcher
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeKnownAuthoritiesFetcher
import app.onym.android.moderation.support.InMemoryGateStateStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import app.onym.android.support.InMemoryOnboardingStore
import app.onym.android.uitests.screens.OnboardingScreenObject
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.security.Security
import java.time.Instant

/**
 * End-to-end coverage of the redesigned first-launch onboarding walk:
 *
 *   welcome → identity → services → (moderation, reserved & disabled)
 *   → recoveryPhrase → done
 *
 * mirroring the iOS suite (`OnboardingUITests.swift`):
 *
 *  1. [smokeDefaultPathCompletesThenNeverAgain] — the shortest happy
 *     path (recommended services kept, recovery deferred via "Remind
 *     me later"), then a dependency rebuild + Activity recreation
 *     proving the completion flag persisted and the walk never
 *     re-presents.
 *  2. [customServicesPathWalk] — the "Choose services myself" hub
 *     touching every seat surface (directory TOFU pin, message
 *     delivery module consent, seeded ACTIVE media row, group
 *     integrity published add), then recovery through the REAL
 *     reveal with the registry-faked biometric.
 *  3. Back navigation revisits the prior step without restarting.
 *  4. A completed-at-boot flag bypasses the walk entirely.
 *  5. The explicit harness contract: with NO onboarding slot
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
 *    and these tests must not rot with them);
 *  - identity flows use a per-suite wiped [IdentitySecretStore]
 *    prefs file (the [IdentitiesUITest] pattern) — the identity
 *    step's bootstrap must actually run, because its snapshot is
 *    what unlocks the step's outcome-gated primary;
 *  - [UITestRegistry.biometricAuthenticator] auto-passes the
 *    recovery reveal's biometric gate.
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

    /** Boot with the moderation seat's registry fakes, so the walk
     *  includes the (otherwise dark) Moderation step. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class ModerationEnabled

    private val onboardingStore = InMemoryOnboardingStore()

    /** Scripted enforcement backend for the @ModerationEnabled walk:
     *  every gate check after consent answers `clear`. */
    private val moderationBackend = ScriptedEnforcementBackendClient()

    private lateinit var identityStore: IdentitySecretStore

    private val discoveryStore = InMemoryDiscoveryStore()
    private val discoveryFetcher = FakeDiscoveryFetcher().apply {
        respond(MANIFEST_URL, fixture("provider-manifest.json"))
        respond(SNAPSHOT_URL, fixture("snapshot-1.json"))
        respond(ENTRY_MANIFEST_URL, fixture("destination-manifest.json"))
    }

    // Non-discovery seams, seeded exactly like DiscoverySettingsUITest
    // so the rest of boot stays offline. The relayer fetcher's one
    // endpoint is the groupIntegrity surface's published list.
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
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
            val app = ApplicationProvider.getApplicationContext<OnymApplication>()
            identityStore = IdentitySecretStore(
                app,
                prefsFileName = "app.onym.android.identity.onboardingwalkuitests",
            ).also { it.wipeAll() }
            UITestRegistry.identitySecretStore = identityStore
            UITestRegistry.relayerStore = relayerStore
            UITestRegistry.relayerFetcher = relayerFetcher
            UITestRegistry.contractsStore = contractsStore
            UITestRegistry.contractsFetcher = contractsFetcher
            UITestRegistry.discoveryFetcher = discoveryFetcher
            UITestRegistry.discoveryStore = discoveryStore
            UITestRegistry.discoveryClock = { FIXTURE_ERA }
            UITestRegistry.biometricAuthenticator = AlwaysSucceedsAuthenticator
            // The seeded, unpinned default source the directory
            // surface TOFU-reviews (production seeding is suppressed
            // under the harness).
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
            if (description.getAnnotation(ModerationEnabled::class.java) != null) {
                UITestRegistry.moderationBackend = moderationBackend
                UITestRegistry.moderationAuthorityClient = FakeAuthorityClient()
                UITestRegistry.moderationAttestation = FakeDeviceAttestationProvider()
                UITestRegistry.moderationMandateStore = InMemoryMandateStore()
                UITestRegistry.moderationGateStateStore = InMemoryGateStateStore()
                UITestRegistry.moderationAuthoritiesFetcher = FakeKnownAuthoritiesFetcher()
                UITestRegistry.moderationManifestFetcher = FakeAuthorityManifestFetcher()
            }
            if (description.getAnnotation(NoOnboardingSlot::class.java) == null) {
                if (description.getAnnotation(AlreadyCompleted::class.java) != null) {
                    runBlocking { onboardingStore.markCompleted() }
                }
                UITestRegistry.onboardingStore = onboardingStore
            }
            UITestRegistry.enabled = true
            app.rebuildDependenciesForTest()
        }

        override fun finished(description: Description) {
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ─── (1) default-path smoke + persistence ─────────────────────

    /**
     * The shortest happy path a real first launch takes: welcome →
     * identity (wait out the bootstrap gate) → services (keep the
     * preselected recommended setup) → recovery (primary locked,
     * defer via "Remind me later") → done ("Start messaging") → tab
     * shell. Then a dependency rebuild + Activity recreation proves
     * the completion flag persisted and the walk never re-presents —
     * the in-process equivalent of the iOS relaunch without
     * `--reset-keychain`.
     */
    @Test
    fun smokeDefaultPathCompletesThenNeverAgain() {
        val onboarding = OnboardingScreenObject(composeRule)

        // Welcome: unskippable; the primary reads "Create my
        // identity".
        onboarding.awaitStep(OnboardingStep.Welcome)
        onboarding.assertNoSkip(OnboardingStep.Welcome)
        onboarding.assertPrimaryLabel(OnboardingStep.Welcome, "Create my identity")
        onboarding.tapPrimary(OnboardingStep.Welcome)

        // Identity: no skip; Continue is outcome-gated on the key
        // bootstrap yielding a snapshot — wait for the unlock.
        onboarding.awaitStep(OnboardingStep.Identity)
        onboarding.assertNoSkip(OnboardingStep.Identity)
        onboarding.awaitPrimaryEnabled(OnboardingStep.Identity)
        onboarding.tapPrimary(OnboardingStep.Identity)

        // Services: SEEDED — recommended card preselected, primary
        // enabled immediately. Keep the defaults. Nothing is pinned
        // yet (pin-on-accept runs on leaving the step forward).
        onboarding.awaitStep(OnboardingStep.Services)
        onboarding.awaitRecommendedCard()
        onboarding.primaryButton(OnboardingStep.Services).assertIsEnabled()
        assertTrue(
            discoveryStore.loadConfigurationBlocking().sources
                .all { it.pinnedOperatorKeyHex == null },
        )
        onboarding.tapPrimary(OnboardingStep.Services)

        // Moderation is RESERVED (flag off): the step must never
        // present — the walk goes straight to recoveryPhrase.
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.assertStepAbsent(OnboardingStep.Moderation)

        // PIN-ON-ACCEPT: advancing past services with Recommended
        // selected is the explicit confirm of the seeded directory —
        // under the harness (fixture fetcher + pinned clock) the
        // programmatic TOFU must SUCCEED and pin the operator key.
        // Async in the host scope, so poll.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            discoveryStore.loadConfigurationBlocking().sources
                .any { it.pinnedOperatorKeyHex == OPERATOR_KEY_HEX }
        }

        // Recovery: the primary ("I've written it down") stays
        // disabled before any reveal; the deferral reads "Remind me
        // later" — the only skip in the whole walk.
        onboarding.awaitRecoveryStatus()
        onboarding.assertPrimaryDisabled(OnboardingStep.RecoveryPhrase)
        onboarding.assertPrimaryLabel(OnboardingStep.RecoveryPhrase, "I've written it down")
        onboarding.skipButton(OnboardingStep.RecoveryPhrase)
            .assertTextEquals("Remind me later")
        onboarding.tapSkip(OnboardingStep.RecoveryPhrase)

        // Skip → Done → Back must RE-LOCK the gate: the surviving
        // Skipped outcome is a decision to defer, not proof of a
        // reveal — "I've written it down" renders disabled again
        // (the outcomeSatisfiesGate rule, pinned end-to-end).
        onboarding.awaitStep(OnboardingStep.Done)
        onboarding.tapBack(OnboardingStep.Done)
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.assertPrimaryDisabled(OnboardingStep.RecoveryPhrase)
        onboarding.tapSkip(OnboardingStep.RecoveryPhrase)

        // Done: the summary's directory row reflects the pin honestly
        // — fingerprint detail, no "Not confirmed" trailing — and
        // "Start messaging" completes; the tab shell mounts.
        onboarding.awaitStep(OnboardingStep.Done)
        onboarding.awaitDoneSummary()
        onboarding.assertDoneDirectoryPinned("ea4a 6c63 e29c 520a")
        onboarding.assertPrimaryLabel(OnboardingStep.Done, "Start messaging")
        onboarding.tapPrimary(OnboardingStep.Done)
        onboarding.awaitTabShell()
        assertTrue("completion flag must be persisted", onboardingStore.completed)
        assertEquals("flag written exactly once", 1, onboardingStore.markCount)

        // Re-resolution: rebuild the dependency graph (fresh gate
        // decision off the same store) and recreate the Activity —
        // the walk must NOT reappear.
        val app = ApplicationProvider.getApplicationContext<OnymApplication>()
        app.rebuildDependenciesForTest()
        composeRule.activityRule.scenario.recreate()
        onboarding.awaitTabShell()
        onboarding.assertNotShown()
        assertEquals("re-resolution must not re-write the flag", 1, onboardingStore.markCount)
    }

    // ─── (1b) the walk WITH the moderation step ────────────────────

    /**
     * With the moderation seat's fakes registered the reserved step
     * enters the sequence between services and recoveryPhrase, its
     * consent surface consents through the scripted backend
     * (challenge → enroll → mandate → countersign), and the recorded
     * outcome unlocks the step's Continue. The walk then completes
     * and the tab shell mounts — the gate's post-consent check
     * answers the scripted `clear`.
     */
    @Test
    @ModerationEnabled
    fun moderationStepConsentsThroughTheWalk() {
        val onboarding = OnboardingScreenObject(composeRule)

        onboarding.awaitStep(OnboardingStep.Welcome)
        onboarding.tapPrimary(OnboardingStep.Welcome)
        onboarding.awaitStep(OnboardingStep.Identity)
        onboarding.awaitPrimaryEnabled(OnboardingStep.Identity)
        onboarding.tapPrimary(OnboardingStep.Identity)
        onboarding.awaitStep(OnboardingStep.Services)
        onboarding.awaitRecommendedCard()
        onboarding.tapPrimary(OnboardingStep.Services)

        // The moderation step presents; its primary stays locked
        // until the consent surface records an outcome.
        onboarding.awaitStep(OnboardingStep.Moderation)
        onboarding.assertPrimaryDisabled(OnboardingStep.Moderation)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(
                hasTestTag("moderation.consent.agree"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // The terms must be VISIBLE, not merely composed: inside the
        // step's scrolling host a weighted terms body once measured to
        // zero height — invisible terms with a live Agree below them.
        composeRule.onNodeWithTag("moderation.consent.terms", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("moderation.consent.agree", useUnmergedTree = true)
            .performClick()
        // Consent ran the full scripted path and unlocked Continue.
        onboarding.awaitPrimaryEnabled(OnboardingStep.Moderation)
        assertTrue(moderationBackend.lastEnrollment != null)
        assertTrue(moderationBackend.lastCountersignedBytes != null)
        onboarding.tapPrimary(OnboardingStep.Moderation)

        // The rest of the walk is the smoke path's shape.
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.tapSkip(OnboardingStep.RecoveryPhrase)
        onboarding.awaitStep(OnboardingStep.Done)
        onboarding.awaitDoneSummary()
        onboarding.tapPrimary(OnboardingStep.Done)
        onboarding.awaitTabShell()
    }

    // ─── (2) the custom-services hub walk ─────────────────────────

    /**
     * The "Choose services myself" branch, touching every seat
     * surface (the Android port of the iOS
     * `test_onboardingWalk_customServicesPath_thenNeverAgain` minus
     * the moderation leg, which is flag-off on Android):
     *
     *  - directory FIRST: TOFU-review the seeded source, check the
     *    fixture fingerprint, pin — so the other seats' catalogs have
     *    a pinned provider to draw from;
     *  - messageDelivery: seeded relay in use (and per fan-out
     *    semantics showing NO PRIMARY/BACKUP role chips), then
     *    consent to the fixture courier through the existing
     *    ModuleConsentScreen;
     *  - mediaDelivery: the seeded Blossom endpoint renders ACTIVE —
     *    keep it;
     *  - groupIntegrity: auto-populate suppressed, add the testnet
     *    relayer from the published list; the footnote under the
     *    default RANDOM strategy names the random pick (and no
     *    PRIMARY chip renders);
     *
     * then hub Done, moderation absent, recovery via the REAL reveal
     * (biometric faked through the registry slot), and the done
     * summary.
     */
    @Test
    fun customServicesPathWalk() {
        val onboarding = OnboardingScreenObject(composeRule)

        onboarding.awaitStep(OnboardingStep.Welcome)
        onboarding.tapPrimary(OnboardingStep.Welcome)
        onboarding.awaitStep(OnboardingStep.Identity)
        onboarding.awaitPrimaryEnabled(OnboardingStep.Identity)
        onboarding.tapPrimary(OnboardingStep.Identity)

        // Services → the hub.
        onboarding.awaitStep(OnboardingStep.Services)
        onboarding.awaitRecommendedCard()
        onboarding.tapCustomCard()
        onboarding.awaitHub()

        // Hub → directory: TOFU pin the seeded source.
        onboarding.openHubRow("directory")
        onboarding.awaitDirectoryConfirm()
        onboarding.tapDirectoryConfirm()
        onboarding.awaitDirectoryFingerprint()
        // First 16 hex chars of the fixture operator key, grouped in
        // 4s — DiscoverySource.fingerprint(OPERATOR_KEY_HEX).
        onboarding.assertDirectoryFingerprint("ea4a 6c63 e29c 520a")
        // Nothing pinned before the confirm (the TOFU contract).
        assertTrue(
            discoveryStore.loadConfigurationBlocking().sources
                .all { it.pinnedOperatorKeyHex == null },
        )
        onboarding.tapDirectoryPin()
        onboarding.awaitDirectoryAdded()
        assertEquals(
            OPERATOR_KEY_HEX,
            discoveryStore.loadConfigurationBlocking().sources
                .single().pinnedOperatorKeyHex,
        )
        onboarding.tapSeatBack("directory")
        onboarding.awaitHub()

        // Hub → messageDelivery: seeded relay in use; fan-out means
        // no per-row role chips. Consent to the fixture courier
        // through the same sheet Settings uses (courier-free-v1 is
        // the free offer).
        onboarding.openHubRow("messageDelivery")
        onboarding.awaitConfiguredRow("messageDelivery", NOSTR_SEED_URL)
        onboarding.assertNoRoleChips("messageDelivery", NOSTR_SEED_URL)
        onboarding.awaitMessageCatalogRow(COURIER_COMPONENT_ID)
        onboarding.openMessageCatalogRow(COURIER_COMPONENT_ID)
        onboarding.awaitConsentSheet()
        onboarding.tapConsentOffer(COURIER_OFFER_ID)
        onboarding.awaitConsentAcceptEnabled()
        onboarding.tapConsentAccept()
        onboarding.awaitConsentDone()
        onboarding.tapConsentDismiss()
        onboarding.tapSeatBack("messageDelivery")
        onboarding.awaitHub()

        // Hub → mediaDelivery: the seeded Blossom endpoint is the
        // ACTIVE pick (first row only — uploads target it); keep it.
        onboarding.openHubRow("mediaDelivery")
        onboarding.awaitConfiguredRow("mediaDelivery", BLOSSOM_SEED_URL)
        onboarding.assertActiveChip("mediaDelivery", BLOSSOM_SEED_URL)
        onboarding.tapSeatBack("mediaDelivery")
        onboarding.awaitHub()

        // Hub → groupIntegrity: configuration starts empty
        // (auto-populate suppressed during onboarding); add the
        // testnet relayer from the published list. Under the default
        // RANDOM strategy the footnote names the random pick and the
        // configured row carries no PRIMARY chip.
        onboarding.openHubRow("groupIntegrity")
        onboarding.awaitNotaryPublishedRow(RELAYER_URL)
        onboarding.tapNotaryAdd(RELAYER_URL)
        onboarding.awaitConfiguredRow("groupIntegrity", RELAYER_URL)
        onboarding.assertNoRoleChips("groupIntegrity", RELAYER_URL)
        onboarding.assertNotaryFootnoteMentionsRandom()
        onboarding.tapSeatBack("groupIntegrity")
        onboarding.awaitHub()

        // Close the hub — three seats configured by hand.
        onboarding.tapHubDone()
        onboarding.awaitStep(OnboardingStep.Services)
        onboarding.tapPrimary(OnboardingStep.Services)

        // Moderation absent (reserved, flag off).
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.assertStepAbsent(OnboardingStep.Moderation)

        // Recovery through the REAL reveal: the primary is locked,
        // the reveal affordance opens RecoveryPhraseBackupScreen with
        // the faked biometric auto-passing; revealing the words is
        // what unlocks "I've written it down" (the verify quiz has
        // its own coverage in RecoveryPhraseBackupScreenTest).
        onboarding.awaitRecoveryStatus()
        onboarding.assertPrimaryDisabled(OnboardingStep.RecoveryPhrase)
        onboarding.recoveryRevealButton().assertIsDisplayed()
        onboarding.tapRecoveryReveal()
        onboarding.awaitBackupIntro()
        onboarding.awaitBackupIntroContinueEnabled()
        onboarding.tapBackupIntroContinue()
        onboarding.awaitBackupRevealCard()
        onboarding.tapBackupRevealCard()
        // Leave the backup surface without the quiz — the reveal
        // alone is the step's outcome.
        onboarding.tapBackupBack()
        onboarding.awaitStep(OnboardingStep.RecoveryPhrase)
        onboarding.awaitPrimaryEnabled(OnboardingStep.RecoveryPhrase)
        onboarding.tapPrimary(OnboardingStep.RecoveryPhrase)

        // Done: summary card renders alongside the frame; the
        // hub-pinned directory shows its fingerprint (never "Not
        // confirmed").
        onboarding.awaitStep(OnboardingStep.Done)
        onboarding.awaitDoneSummary()
        onboarding.assertDoneDirectoryPinned("ea4a 6c63 e29c 520a")
        onboarding.tapPrimary(OnboardingStep.Done)
        onboarding.awaitTabShell()
        assertTrue(onboardingStore.completed)
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

    // ─── (4) completed flag at boot ───────────────────────────────

    @Test
    @AlreadyCompleted
    fun completedFlag_atBoot_bypassesTheWalk() {
        val onboarding = OnboardingScreenObject(composeRule)
        onboarding.awaitTabShell()
        onboarding.assertNotShown()
    }

    // ─── (5) explicit harness bypass contract ─────────────────────

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
        const val COURIER_COMPONENT_ID = "onym:component:onym-courier"
        const val COURIER_OFFER_ID = "courier-free-v1"
        const val OPERATOR_KEY_HEX =
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c"
        const val RELAYER_URL = "https://relayer-testnet.onym.chat"

        /** The hardcoded transport seeds the seat surfaces render
         *  when the published-feed fetchers are left null
         *  (no-network contract). */
        const val NOSTR_SEED_URL = "wss://nostr.onym.app"
        const val BLOSSOM_SEED_URL = "https://blossom.onym.app"

        /** Inside the fixtures' validity window (snapshot expires
         *  2026-09-12) — frozen so the suite can't rot. */
        val FIXTURE_ERA: Instant = Instant.parse("2026-08-14T00:00:00Z")

        /** Auto-passing fake for [UITestRegistry.biometricAuthenticator]
         *  — the recovery reveal's biometric gate. */
        val AlwaysSucceedsAuthenticator =
            object : app.onym.android.recovery.BiometricAuthenticator {
                override suspend fun authenticate(title: String, subtitle: String?) = Unit
            }

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
