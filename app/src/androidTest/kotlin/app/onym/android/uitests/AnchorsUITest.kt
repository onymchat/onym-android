package app.onym.android.uitests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.UITestRegistry
import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.ContractEntry
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.ContractRelease
import app.onym.android.chain.ContractsManifest
import app.onym.android.chain.GovernanceType
import app.onym.android.support.FakeContractsManifestFetcher
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.InMemoryAnchorSelectionStore
import app.onym.android.support.InMemoryRelayerSelectionStore
import app.onym.android.uitests.screens.AnchorsScreenObjects
import app.onym.android.uitests.screens.SettingsScreenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class AnchorsUITest {

    // Two-release fixture; only testnet contracts → mainnet stays
    // disabled (matches today's manifest reality).
    private val v002 = ContractRelease(
        release = "v0.0.2",
        publishedAt = Instant.parse("2026-05-02T00:00:00Z"),
        contracts = listOf(
            ContractEntry(ContractNetwork.Testnet, GovernanceType.Anarchy, "C-A-2"),
            ContractEntry(ContractNetwork.Testnet, GovernanceType.Democracy, "C-D-2"),
        ),
    )
    private val v001 = ContractRelease(
        release = "v0.0.1",
        publishedAt = Instant.parse("2026-05-01T00:00:00Z"),
        contracts = listOf(
            ContractEntry(ContractNetwork.Testnet, GovernanceType.Anarchy, "C-A-1"),
        ),
    )
    private val manifest = ContractsManifest(version = 1, releases = listOf(v002, v001))

    private val contractsStore = InMemoryAnchorSelectionStore()
    /** rawJson must match [manifest] above so the store-side wait
     *  (`loadCachedManifestBlocking()`) decodes the same shape the
     *  fetcher returns. The default empty rawJson on the fake would
     *  decode to 0 releases. */
    private val manifestRawJson = """
        {
          "version": 1,
          "releases": [
            {
              "release": "v0.0.2",
              "publishedAt": "2026-05-02T00:00:00Z",
              "contracts": [
                { "network": "testnet", "type": "anarchy",   "id": "C-A-2" },
                { "network": "testnet", "type": "democracy", "id": "C-D-2" }
              ]
            },
            {
              "release": "v0.0.1",
              "publishedAt": "2026-05-01T00:00:00Z",
              "contracts": [
                { "network": "testnet", "type": "anarchy", "id": "C-A-1" }
              ]
            }
          ]
        }
    """.trimIndent()

    private val contractsFetcher = FakeContractsManifestFetcher(
        FakeContractsManifestFetcher.Mode.Succeeds(manifest, rawJson = manifestRawJson)
    )

    /** Same trick as `RelayerSettingsUITest` — populate the registry
     *  BEFORE the compose rule launches `MainActivity`. */
    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            // Seed relayer with one endpoint + hasUserInteracted=true
            // so the auto-populate path doesn't race with these tests
            // (which only care about anchors).
            val relayerStore = InMemoryRelayerSelectionStore().apply {
                kotlinx.coroutines.runBlocking {
                    saveConfiguration(
                        app.onym.android.chain.RelayerConfiguration(
                            endpoints = listOf(
                                app.onym.android.chain.RelayerEndpoint("Onym Testnet", "https://relayer-testnet.onym.chat", listOf("testnet"))
                            ),
                            hasUserInteracted = true,
                        )
                    )
                }
            }
            UITestRegistry.relayerStore = relayerStore
            UITestRegistry.relayerFetcher = FakeKnownRelayersFetcher(
                FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())
            )
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

    @Test
    fun drillDown_pickVersion_persistsViaStore() {
        val settings = SettingsScreenObject(composeRule)
        val anchors = AnchorsScreenObjects(composeRule)

        settings.tapAnchorsRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            contractsStore.loadCachedManifestBlocking()?.releases?.size == 2
        }
        anchors.tapNetwork(ContractNetwork.Testnet.wireValue)
        anchors.tapType(GovernanceType.Anarchy.wireValue)
        anchors.tapVersion("v0.0.1")  // older release; default is v0.0.2

        val key = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy)
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            contractsStore.loadSelectionsBlocking()[key] == "v0.0.1"
        }
        assertEquals("v0.0.1", contractsStore.loadSelectionsBlocking()[key])
    }

    @Test
    fun resetToDefault_clearsExplicitPick() {
        val settings = SettingsScreenObject(composeRule)
        val anchors = AnchorsScreenObjects(composeRule)
        settings.tapAnchorsRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            contractsStore.loadCachedManifestBlocking()?.releases?.size == 2
        }
        anchors.tapNetwork(ContractNetwork.Testnet.wireValue)
        anchors.tapType(GovernanceType.Anarchy.wireValue)
        anchors.tapVersion("v0.0.1")
        val key = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy)
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            contractsStore.loadSelectionsBlocking()[key] == "v0.0.1"
        }

        // After picking the version, the AnchorsVersionScreen pops
        // back to AnchorsNetworkScreen. Drill straight into the
        // type row again to land on the version screen + tap reset.
        anchors.tapType(GovernanceType.Anarchy.wireValue)
        anchors.tapReset()

        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            contractsStore.loadSelectionsBlocking()[key] == null
        }
        assertNull(contractsStore.loadSelectionsBlocking()[key])
    }

    @Test
    fun mainnetRow_disabledWhenNoMainnetContracts() {
        val settings = SettingsScreenObject(composeRule)
        val anchors = AnchorsScreenObjects(composeRule)
        settings.tapAnchorsRow()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            contractsStore.loadCachedManifestBlocking()?.releases?.size == 2
        }
        // Both rows render — but the Mainnet row carries the "No
        // contracts yet" subtitle and isn't tappable. Assert via the
        // localized subtitle text.
        anchors.assertNetworkRowVisible(ContractNetwork.Public.wireValue)
        composeRule.onNodeWithText("No contracts yet").assertIsDisplayed()
    }
}

private fun InMemoryAnchorSelectionStore.loadCachedManifestBlocking(): ContractsManifest? =
    kotlinx.coroutines.runBlocking { loadCachedManifest() }

private fun InMemoryAnchorSelectionStore.loadSelectionsBlocking(): Map<AnchorSelectionKey, String> =
    kotlinx.coroutines.runBlocking { loadSelections() }
