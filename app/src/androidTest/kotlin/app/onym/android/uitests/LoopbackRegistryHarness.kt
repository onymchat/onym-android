package app.onym.android.uitests

import app.onym.android.UITestRegistry
import app.onym.android.chain.ContractEntry
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.ContractRelease
import app.onym.android.chain.ContractsManifest
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.RelayerConfiguration
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.support.FakeContractsManifestFetcher
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.InMemoryAnchorSelectionStore
import app.onym.android.support.InMemoryChainLedger
import app.onym.android.support.InMemoryRelayerSelectionStore
import app.onym.android.support.LedgerSepContractTransport
import app.onym.android.support.LoopbackBlossomClient
import app.onym.android.support.LoopbackInboxTransport
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Shared offline harness for the chat UI tests. Populates
 * [UITestRegistry] with the in-memory fakes that let identities exchange
 * invitations / messages / receipts and anchor a Tyranny group with no
 * network:
 *   - [LoopbackInboxTransport] — in-process store-and-forward inbox.
 *   - [InMemoryChainLedger] + [LedgerSepContractTransport] — the same
 *     in-memory chain state feeds `create_group` writes and
 *     `get_commitment` reads, so a group anchors then verifies.
 *   - [LoopbackBlossomClient] — in-process media blobs for image/video.
 *
 * Call from a `TestWatcher.starting()` after building the per-test
 * [IdentitySecretStore] + [InMemoryChainLedger], then invoke
 * `rebuildDependenciesForTest()` so the application picks up the fakes.
 */
object LoopbackRegistryHarness {
    fun configure(identityStore: IdentitySecretStore, chainLedger: InMemoryChainLedger) {
        UITestRegistry.identitySecretStore = identityStore
        UITestRegistry.relayerStore = InMemoryRelayerSelectionStore().apply {
            runBlocking {
                saveConfiguration(
                    RelayerConfiguration(
                        endpoints = listOf(
                            RelayerEndpoint("test", "https://relayer.test.invalid", listOf("testnet")),
                        ),
                        hasUserInteracted = true,
                    ),
                )
            }
        }
        UITestRegistry.relayerFetcher = FakeKnownRelayersFetcher(
            FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()),
        )
        UITestRegistry.contractsStore = InMemoryAnchorSelectionStore()
        UITestRegistry.contractsFetcher = FakeContractsManifestFetcher(
            FakeContractsManifestFetcher.Mode.Succeeds(
                ContractsManifest(
                    version = 1,
                    releases = listOf(
                        ContractRelease(
                            release = "v0.0.2",
                            publishedAt = Instant.parse("2023-11-14T00:00:02Z"),
                            contracts = listOf(
                                ContractEntry(
                                    network = ContractNetwork.Testnet,
                                    type = GovernanceType.Tyranny,
                                    id = "CUITESTTYRANNYTESTNET00000000000000000000000000000000000",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        UITestRegistry.inboxTransport = LoopbackInboxTransport()
        UITestRegistry.contractTransportFactory = { LedgerSepContractTransport(chainLedger) }
        UITestRegistry.blossomClient = LoopbackBlossomClient()
        UITestRegistry.enabled = true
    }
}
