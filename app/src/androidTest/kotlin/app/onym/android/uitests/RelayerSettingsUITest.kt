package app.onym.android.uitests

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.UITestRegistry
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.InMemoryRelayerSelectionStore
import app.onym.android.uitests.screens.RelayerSettingsScreenObject
import app.onym.android.uitests.screens.SettingsScreenObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage of the multi-relayer settings flow + the
 * PR #22 default-random-preseed behaviour.
 *
 * Test sequence per case:
 *
 *  1. `@Before` resets [UITestRegistry] + seeds the in-memory fakes
 *     with a known published list. `enabled = true` flips
 *     [app.onym.android.OnymApplication]'s wiring branch.
 *  2. The compose rule launches `MainActivity` with the test
 *     application — `onCreate` reads the registry, wires fakes,
 *     and `RelayerRepository.start()` runs the auto-populate path
 *     because the in-memory store starts cold (`hasUserInteracted
 *     = false`).
 *  3. Each test drives the UI through the page objects + asserts
 *     against the fake store's recorded state.
 *
 * Fake-only stores (no contracts/anchors needed for these tests)
 * are seeded with empty manifests so the Anchors row stays
 * disabled — these tests don't touch it.
 */
@RunWith(AndroidJUnit4::class)
class RelayerSettingsUITest {

    private val testnet = RelayerEndpoint("Onym Testnet", "https://relayer-testnet.onym.chat", listOf("testnet"))
    private val mainnet = RelayerEndpoint("Onym Mainnet", "https://relayer.onym.chat", listOf("public"))

    private val relayerStore = InMemoryRelayerSelectionStore()
    private val relayerFetcher = FakeKnownRelayersFetcher(
        FakeKnownRelayersFetcher.Mode.Succeeds(listOf(testnet, mainnet))
    )
    private val contractsStore = app.onym.android.support.InMemoryAnchorSelectionStore()
    private val contractsFetcher = app.onym.android.support.FakeContractsManifestFetcher(
        app.onym.android.support.FakeContractsManifestFetcher.Mode.Succeeds(
            app.onym.android.chain.ContractsManifest(version = 1, releases = emptyList())
        )
    )

    /** Ordered first (`order = 0`) so the registry is populated +
     *  the cached [app.onym.android.OnymApplication.dependencies]
     *  invalidated BEFORE [composeRule] launches `MainActivity`.
     *  JUnit's default rule ordering is unspecified across JVMs;
     *  the explicit `order` arg pins it. */
    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            UITestRegistry.relayerStore = relayerStore
            UITestRegistry.relayerFetcher = relayerFetcher
            UITestRegistry.contractsStore = contractsStore
            UITestRegistry.contractsFetcher = contractsFetcher
            UITestRegistry.enabled = true
            val app = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<app.onym.android.OnymApplication>()
            app.rebuildDependenciesForTest()
        }
        override fun finished(description: Description) {
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ─── auto-populate ────────────────────────────────────────────

    @Test
    fun autoPopulate_seedsConfiguredListWithPublishedEntries_onFirstLaunch() {
        val settings = SettingsScreenObject(composeRule)
        val relayer = RelayerSettingsScreenObject(composeRule)

        settings.tapRelayerRow()

        // Wait for the boot fetch to finish and the snapshot to flip
        // from cold (empty) to auto-populated (testnet + mainnet).
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 2
        }
        relayer.assertConfigured(testnet.url)
        relayer.assertConfigured(mainnet.url)
        // Strategy is RANDOM by default after auto-populate.
        assertEquals(
            app.onym.android.chain.RelayerStrategy.RANDOM,
            relayerStore.loadConfigurationBlocking().strategy,
        )
    }

    // ─── intents ──────────────────────────────────────────────────

    @Test
    fun markPrimary_persistsViaStore() {
        val settings = SettingsScreenObject(composeRule)
        val relayer = RelayerSettingsScreenObject(composeRule)
        settings.tapRelayerRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 2
        }

        relayer.tapMarkPrimary(testnet.url)
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().primaryUrl == testnet.url
        }
        assertEquals(testnet.url, relayerStore.loadConfigurationBlocking().primaryUrl)
    }

    @Test
    fun switchStrategy_toPrimary_persistsViaStore() {
        val settings = SettingsScreenObject(composeRule)
        val relayer = RelayerSettingsScreenObject(composeRule)
        settings.tapRelayerRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 2
        }

        relayer.tapStrategy("primary")
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().strategy ==
                app.onym.android.chain.RelayerStrategy.PRIMARY
        }
    }

    @Test
    fun addCustomUrl_appendsAsCustomEndpoint() {
        val settings = SettingsScreenObject(composeRule)
        val relayer = RelayerSettingsScreenObject(composeRule)
        settings.tapRelayerRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 2
        }

        relayer.typeCustomUrl("https://custom.example/relayer")
        relayer.tapAddCustom()
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 3
        }
        val endpoints = relayerStore.loadConfigurationBlocking().endpoints
        assertEquals(listOf("custom"), endpoints.last().networks)
        assertEquals("https://custom.example/relayer", endpoints.last().url)
    }

    @Test
    fun swipeDelete_removesEndpointFromStore() {
        val settings = SettingsScreenObject(composeRule)
        val relayer = RelayerSettingsScreenObject(composeRule)
        settings.tapRelayerRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 2
        }

        relayer.swipeDelete(mainnet.url)
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 1
        }
        assertEquals(testnet.url, relayerStore.loadConfigurationBlocking().endpoints.single().url)
        assertNull(
            "removing non-primary keeps primaryUrl null",
            relayerStore.loadConfigurationBlocking().primaryUrl,
        )
    }

    @Test
    fun deletingPrimary_clearsPrimaryMarker() {
        val settings = SettingsScreenObject(composeRule)
        val relayer = RelayerSettingsScreenObject(composeRule)
        settings.tapRelayerRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().endpoints.size == 2
        }
        relayer.tapMarkPrimary(testnet.url)
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().primaryUrl == testnet.url
        }

        relayer.swipeDelete(testnet.url)

        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            relayerStore.loadConfigurationBlocking().primaryUrl == null
        }
        assertTrue(
            "remaining endpoint count drops to 1",
            relayerStore.loadConfigurationBlocking().endpoints.size == 1,
        )
    }
}

/** `runBlocking { loadConfiguration() }` shorthand — the page objects'
 *  `waitUntil` callbacks run on the main thread, where `runBlocking`
 *  is safe (DataStore-shaped suspend fn that resolves locally). */
private fun InMemoryRelayerSelectionStore.loadConfigurationBlocking(): app.onym.android.chain.RelayerConfiguration =
    kotlinx.coroutines.runBlocking { loadConfiguration() }
