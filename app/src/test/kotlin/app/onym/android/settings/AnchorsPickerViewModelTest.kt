@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.settings

import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.AppNetwork
import app.onym.android.chain.ContractEntry
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.ContractRelease
import app.onym.android.chain.ContractsManifest
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.StaticNetworkPreferenceProvider
import app.onym.android.support.FakeContractsManifestFetcher
import app.onym.android.support.InMemoryAnchorSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AnchorsPickerViewModelTest {

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
    private val twoReleases = ContractsManifest(version = 1, releases = listOf(v002, v001))

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ─── state mirror ─────────────────────────────────────────────

    @Test
    fun state_indicatesNoManifestUntilStartLands() = runTest {
        val (_, vm) = makeViewModel(seedManifest = null)

        val s = vm.state.value
        assertFalse(s.hasManifest)
        // Both networks unavailable when no manifest.
        assertEquals(false, s.networkAvailability[ContractNetwork.Testnet])
        assertEquals(false, s.networkAvailability[ContractNetwork.Public])
    }

    @Test
    fun state_indicatesTestnetAvailableMainnetNot_whenOnlyTestnetContractsPublished() = runTest {
        val (_, vm) = makeViewModel(seedManifest = twoReleases)

        val s = vm.state.value
        assertTrue(s.hasManifest)
        assertEquals(true, s.networkAvailability[ContractNetwork.Testnet])
        assertEquals(
            "Mainnet row stays disabled until a public-network contract ships",
            false, s.networkAvailability[ContractNetwork.Public],
        )
    }

    // ─── networkRows ──────────────────────────────────────────────

    @Test
    fun networkRows_subtitleSaysLatestWhenNoExplicitPick() = runTest {
        val (_, vm) = makeViewModel(seedManifest = twoReleases)
        val rows = vm.networkRows(ContractNetwork.Testnet)

        val anarchy = rows.first { it.type == GovernanceType.Anarchy }
        assertEquals("v0.0.2", anarchy.resolvedRelease)
        assertFalse(anarchy.isExplicit)
    }

    @Test
    fun networkRows_subtitleSaysSelectedWhenExplicitPickInPlace() = runTest {
        val (repo, vm) = makeViewModel(seedManifest = twoReleases)
        repo.setSelection(
            key = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy),
            releaseTag = "v0.0.1",
        )
        yield()

        val rows = vm.networkRows(ContractNetwork.Testnet)
        val anarchy = rows.first { it.type == GovernanceType.Anarchy }
        assertEquals("v0.0.1", anarchy.resolvedRelease)
        assertTrue(anarchy.isExplicit)
    }

    @Test
    fun networkRows_resolvedReleaseIsNullWhenNoContractForSlot() = runTest {
        val (_, vm) = makeViewModel(seedManifest = twoReleases)
        val rows = vm.networkRows(ContractNetwork.Testnet)
        val oligarchy = rows.first { it.type == GovernanceType.Oligarchy }
        assertNull("no contract for (testnet, oligarchy) in this fixture", oligarchy.resolvedRelease)
    }

    // ─── versionRows ──────────────────────────────────────────────

    @Test
    fun versionRows_listsOnlyReleasesThatContainContractForSlot() = runTest {
        val (_, vm) = makeViewModel(seedManifest = twoReleases)
        val rowsAnarchy = vm.versionRows(ContractNetwork.Testnet, GovernanceType.Anarchy)
        // Both v0.0.2 and v0.0.1 carry a testnet/anarchy contract.
        assertEquals(listOf("v0.0.2", "v0.0.1"), rowsAnarchy.map { it.release.release })

        val rowsDemocracy = vm.versionRows(ContractNetwork.Testnet, GovernanceType.Democracy)
        // Only v0.0.2 has a testnet/democracy contract.
        assertEquals(listOf("v0.0.2"), rowsDemocracy.map { it.release.release })
    }

    @Test
    fun versionRows_marksLatestAsCurrentlySelectedWhenNoExplicitPick() = runTest {
        val (_, vm) = makeViewModel(seedManifest = twoReleases)
        val rows = vm.versionRows(ContractNetwork.Testnet, GovernanceType.Anarchy)
        val v002Row = rows.first { it.release.release == "v0.0.2" }
        val v001Row = rows.first { it.release.release == "v0.0.1" }
        assertTrue(v002Row.isCurrentlySelected)
        assertFalse(v002Row.isExplicitPick)
        assertFalse(v001Row.isCurrentlySelected)
    }

    // ─── intents ──────────────────────────────────────────────────

    @Test
    fun pickVersion_persistsViaRepository() = runTest {
        val (repo, vm) = makeViewModel(seedManifest = twoReleases)
        vm.pickVersion(ContractNetwork.Testnet, GovernanceType.Anarchy, "v0.0.1")
        yield()

        val key = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy)
        assertEquals("v0.0.1", repo.snapshots.value.selections[key])
    }

    @Test
    fun resetToDefault_clearsExplicitPick() = runTest {
        val (repo, vm) = makeViewModel(seedManifest = twoReleases)
        vm.pickVersion(ContractNetwork.Testnet, GovernanceType.Anarchy, "v0.0.1")
        yield()

        vm.resetToDefault(ContractNetwork.Testnet, GovernanceType.Anarchy)
        yield()

        val key = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy)
        assertNull(repo.snapshots.value.selections[key])
        // Default-to-latest now applies.
        assertEquals("v0.0.2", repo.snapshots.value.binding(key)?.release)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private suspend fun makeViewModel(
        seedManifest: ContractsManifest?,
    ): Pair<ContractsRepository, AnchorsPickerViewModel> {
        val store = InMemoryAnchorSelectionStore()
        val fetcher = if (seedManifest != null) {
            FakeContractsManifestFetcher(FakeContractsManifestFetcher.Mode.Succeeds(seedManifest))
        } else {
            FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Failing(IllegalStateException("not started"))
            )
        }
        val repo = ContractsRepository(store, fetcher)
        if (seedManifest != null) repo.start()
        val vm = AnchorsPickerViewModel(
            repo,
            StaticNetworkPreferenceProvider(AppNetwork.Testnet),
        )
        yield()
        return repo to vm
    }
}
