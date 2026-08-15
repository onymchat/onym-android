package app.onym.android.uitests

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.OnymApplication
import app.onym.android.UITestRegistry
import app.onym.android.discovery.DiscoveryFetchStatus
import app.onym.android.discovery.DiscoverySource
import app.onym.android.discovery.DiscoverySourcesConfiguration
import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import app.onym.android.uitests.screens.AddDiscoveryProviderScreenObject
import app.onym.android.uitests.screens.DiscoverySettingsScreenObject
import app.onym.android.uitests.screens.SettingsScreenObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage of the Discovery settings surfaces (W5 PR A5):
 *
 *  1. Add a provider by manifest URL through the TOFU fingerprint
 *     confirmation — nothing pinned before confirm, source pinned +
 *     catalog entries verified after.
 *  2. A removed source stays gone after a refresh (refresh skips it
 *     entirely — no re-fetch, no re-appearing rows).
 *  3. When the app boots without discovery (registry slots null),
 *     Settings shows no DISCOVERY section at all.
 *
 * Network is replayed from the shared Discovery conformance fixtures
 * (`src/androidTest/resources/fixtures/`, byte-copies of
 * `modules/discovery/src/test/resources/fixtures/` — the copies must
 * stay byte-identical because every signature verifies over the exact
 * bytes against the operator key embedded in the manifest).
 *
 * Shelf life: none — [app.onym.android.UITestRegistry.discoveryClock]
 * pins the trust layer to a fixture-era instant (2026-08-14), inside
 * the signed fixtures' validity windows (`snapshot-1.json` expires
 * 2026-09-12, `provider-manifest.json` 2026-12-31), so the suite
 * stays green after those dates instead of rotting with them. Same
 * injected-clock posture as [OnboardingWalkUITest].
 *
 * Registry discipline follows [RelayerSettingsUITest]: an
 * `order = 0` rule populates [UITestRegistry] (branching on the
 * per-test annotations below) and invalidates the cached
 * dependencies BEFORE the compose rule launches [MainActivity].
 */
@RunWith(AndroidJUnit4::class)
class DiscoverySettingsUITest {

    /** Boot WITHOUT discovery fakes: both registry slots stay null,
     *  so [OnymApplication] constructs no DiscoveryRepository and
     *  every discovery surface must be absent. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class DiscoveryAbsent

    /** Boot with the fixture provider already pinned in the store —
     *  skips the add flow for tests about an *existing* source. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class SeedPinnedSource

    private val discoveryStore = InMemoryDiscoveryStore()
    private val discoveryFetcher = FakeDiscoveryFetcher().apply {
        respond(MANIFEST_URL, fixture("provider-manifest.json"))
        // The snapshot URL is embedded in the signed manifest bytes —
        // it can't be rewritten without breaking the signature, so the
        // fake maps the fixture's own URL.
        respond(SNAPSHOT_URL, fixture("snapshot-1.json"))
        // The snapshot's single (transport.message) entry points at
        // this destination manifest. The discovery-backed Nostr seat
        // adapter may consult the catalog from its own boot refresh
        // once entries exist, so the fake maps it too — keeps that
        // background path deterministic instead of an unmapped-URL
        // IOException.
        respond(ENTRY_MANIFEST_URL, fixture("destination-manifest.json"))
    }

    // Non-discovery seams, seeded exactly like RelayerSettingsUITest
    // so the rest of boot stays offline.
    private val relayerStore = app.onym.android.support.InMemoryRelayerSelectionStore()
    private val relayerFetcher = app.onym.android.support.FakeKnownRelayersFetcher(
        app.onym.android.support.FakeKnownRelayersFetcher.Mode.Succeeds(
            listOf(
                app.onym.android.chain.RelayerEndpoint(
                    "Onym Testnet",
                    "https://relayer-testnet.onym.chat",
                    listOf("testnet"),
                ),
            ),
        ),
    )
    private val contractsStore = app.onym.android.support.InMemoryAnchorSelectionStore()
    private val contractsFetcher = app.onym.android.support.FakeContractsManifestFetcher(
        app.onym.android.support.FakeContractsManifestFetcher.Mode.Succeeds(
            app.onym.android.chain.ContractsManifest(version = 1, releases = emptyList())
        )
    )

    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            UITestRegistry.relayerStore = relayerStore
            UITestRegistry.relayerFetcher = relayerFetcher
            UITestRegistry.contractsStore = contractsStore
            UITestRegistry.contractsFetcher = contractsFetcher
            if (description.getAnnotation(DiscoveryAbsent::class.java) == null) {
                if (description.getAnnotation(SeedPinnedSource::class.java) != null) {
                    runBlocking {
                        discoveryStore.saveConfiguration(
                            DiscoverySourcesConfiguration(
                                sources = listOf(
                                    DiscoverySource(
                                        url = MANIFEST_URL,
                                        label = "discovery.onym.app",
                                        providerId = PROVIDER_ID,
                                        pinnedOperatorKeyHex = OPERATOR_KEY_HEX,
                                    ),
                                ),
                            ),
                        )
                    }
                }
                UITestRegistry.discoveryFetcher = discoveryFetcher
                UITestRegistry.discoveryStore = discoveryStore
                // Fixture-era clock — see the "Shelf life" note above.
                UITestRegistry.discoveryClock = { FIXTURE_ERA }
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

    // ─── (a) add provider by URL through TOFU ─────────────────────

    @Test
    fun addProviderByUrl_pinsKeyOnlyAfterFingerprintConfirm() {
        val settings = SettingsScreenObject(composeRule)
        val discovery = DiscoverySettingsScreenObject(composeRule)
        val add = AddDiscoveryProviderScreenObject(composeRule)

        settings.tapDiscoveryRow()
        discovery.assertEmpty()
        discovery.tapAddProvider()

        add.typeManifestUrl(MANIFEST_URL)
        add.tapFetch()
        add.awaitFingerprint()
        // First 16 hex chars of the fixture operator key, grouped in
        // 4s — DiscoverySource.fingerprint(OPERATOR_KEY_HEX).
        add.assertFingerprint("ea4a 6c63 e29c 520a")

        // TOFU contract: the manifest verified, but NOTHING is
        // persisted until the user confirms the fingerprint.
        assertTrue(
            "no source may be pinned before the fingerprint confirm",
            discoveryStore.loadConfigurationBlocking().sources.isEmpty(),
        )

        add.tapConfirm()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            discoveryStore.loadConfigurationBlocking().sources
                .singleOrNull()?.pinnedOperatorKeyHex == OPERATOR_KEY_HEX
        }
        // Confirm triggered a refresh: the signed snapshot verified
        // and its single entry landed in the cached aggregate.
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            discoveryStore.loadCachedEntriesBlocking().size == 1
        }
        assertEquals(
            PROVIDER_ID,
            discoveryStore.loadCachedEntriesBlocking().single().attribution.providerId,
        )
        // Network trace for the provider's own documents: manifest
        // once for the preview, then manifest + snapshot for the
        // post-confirm refresh. Filtered because the discovery-backed
        // seat adapters may additionally pull the entry's destination
        // manifest from their own background refreshes.
        assertEquals(
            listOf(MANIFEST_URL, MANIFEST_URL, SNAPSHOT_URL),
            discoveryFetcher.fetchedUrls.filter { it == MANIFEST_URL || it == SNAPSHOT_URL },
        )

        add.awaitDone()
        add.tapDone()
        discovery.assertSourceListed(PROVIDER_ID)
    }

    // ─── (b) removed source stays gone after refresh ──────────────

    @Test
    @SeedPinnedSource
    fun removedSource_doesNotReturnAfterRefresh() {
        val settings = SettingsScreenObject(composeRule)
        val discovery = DiscoverySettingsScreenObject(composeRule)

        // Boot refresh (repository.start) verified the seeded pinned
        // source and published its entry.
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            discoveryStore.loadCachedEntriesBlocking().size == 1
        }
        settings.tapDiscoveryRow()
        discovery.assertSourceListed(PROVIDER_ID)

        discovery.tapRemove(PROVIDER_ID)
        discovery.confirmRemove()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            discoveryStore.loadConfigurationBlocking().sources.isEmpty()
        }
        assertTrue(
            "removal drops the source's cached entries immediately",
            discoveryStore.loadCachedEntriesBlocking().isEmpty(),
        )
        discovery.assertEmpty()

        // Refresh again through the app's own dependency graph (the
        // same repository instance the UI observes). The removed
        // source must be skipped entirely: no re-fetch of its
        // manifest, no rows coming back.
        val sourceFetchesBeforeRefresh = discoveryFetcher.fetchedUrls
            .count { it == MANIFEST_URL || it == SNAPSHOT_URL }
        val app = ApplicationProvider.getApplicationContext<OnymApplication>()
        val discoveryUi = checkNotNull(app.dependencies.discovery)
        composeRule.runOnUiThread {
            // viewModelScope is Main.immediate and every seam in the
            // zero-pinned-source refresh path resolves without
            // suspension, so the refresh completes inside this block.
            discoveryUi.makeDiscoverySettingsViewModel().tappedRefresh()
        }
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            discoveryUi.stateFlow.value.fetchStatus is DiscoveryFetchStatus.Success
        }

        assertEquals(
            "refresh must not re-fetch a removed source",
            sourceFetchesBeforeRefresh,
            discoveryFetcher.fetchedUrls.count { it == MANIFEST_URL || it == SNAPSHOT_URL },
        )
        assertTrue(discoveryStore.loadConfigurationBlocking().sources.isEmpty())
        assertTrue(discoveryStore.loadCachedEntriesBlocking().isEmpty())
        discovery.assertEmpty()
    }

    // ─── (c) discovery absent → no DISCOVERY section ──────────────

    @Test
    @DiscoveryAbsent
    fun discoveryAbsent_settingsShowsNoDiscoverySection() {
        SettingsScreenObject(composeRule).assertNoDiscoveryRow()
    }

    // ─── fixtures ─────────────────────────────────────────────────

    private companion object {
        /** URL the user types; any https URL works — the fake maps it
         *  to the signed provider-manifest bytes. */
        const val MANIFEST_URL = "https://discovery.onym.app/manifest.json"

        /** Snapshot URL embedded in the signed manifest fixture. */
        const val SNAPSHOT_URL = "https://discovery.onym.app/catalogs/public-all-seats.json"

        /** Destination-manifest URL embedded in the snapshot's entry. */
        const val ENTRY_MANIFEST_URL = "https://discovery.onym.app/manifests/onym-courier.json"

        /** providerId + operator key baked into the fixture bytes. */
        const val PROVIDER_ID = "onym:component:onym-discovery"
        const val OPERATOR_KEY_HEX =
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c"

        /** Inside the fixtures' validity windows — see "Shelf life". */
        val FIXTURE_ERA: java.time.Instant = java.time.Instant.parse("2026-08-14T00:00:00Z")

        fun fixture(name: String): ByteArray =
            checkNotNull(
                DiscoverySettingsUITest::class.java.classLoader
                    ?.getResourceAsStream("fixtures/$name")
            ) { "missing androidTest fixture: fixtures/$name" }
                .use { it.readBytes() }
    }
}

/** Main-thread-safe suspend shorthands for `waitUntil` polling —
 *  same pattern as [RelayerSettingsUITest]'s store helper. */
private fun InMemoryDiscoveryStore.loadConfigurationBlocking(): DiscoverySourcesConfiguration =
    runBlocking { loadConfiguration() }

private fun InMemoryDiscoveryStore.loadCachedEntriesBlocking() =
    runBlocking { loadCachedEntries() }
