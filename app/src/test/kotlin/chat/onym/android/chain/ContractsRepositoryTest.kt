package chat.onym.android.chain

import chat.onym.android.support.FakeContractsManifestFetcher
import chat.onym.android.support.InMemoryAnchorSelectionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Repository contract + the load-bearing `binding(forKey)`
 * resolution rules (explicit → default-to-latest → null).
 */
class ContractsRepositoryTest {

    private val testnetAnarchyKey = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy)
    private val testnetDemocracyKey = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Democracy)
    private val mainnetAnarchyKey = AnchorSelectionKey(ContractNetwork.Public, GovernanceType.Anarchy)

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

    // ─── binding(forKey) resolution ────────────────────────────────

    @Test
    fun binding_explicitSelectionWins() {
        val state = ContractsState(
            manifest = twoReleases,
            selections = mapOf(testnetAnarchyKey to "v0.0.1"),
        )
        val binding = state.binding(testnetAnarchyKey)
        assertEquals("v0.0.1", binding?.release)
        assertEquals("C-A-1", binding?.contractId)
    }

    @Test
    fun binding_defaultToLatestWhenNoExplicitPick() {
        val state = ContractsState(manifest = twoReleases, selections = emptyMap())
        val binding = state.binding(testnetAnarchyKey)
        assertEquals("v0.0.2", binding?.release)
        assertEquals("C-A-2", binding?.contractId)
    }

    @Test
    fun binding_defaultToLatestWhenExplicitPickIsStale() {
        // User picked a tag that's no longer in the manifest (release
        // was withdrawn, or the user upgraded to a manifest version
        // before the pick existed) → fall through to default-to-latest.
        val state = ContractsState(
            manifest = twoReleases,
            selections = mapOf(testnetAnarchyKey to "v9.9.9"),
        )
        val binding = state.binding(testnetAnarchyKey)
        assertEquals("v0.0.2", binding?.release)
    }

    @Test
    fun binding_returnsNullWhenNoContractForKey() {
        // No contract for (testnet, oligarchy) in either release.
        val state = ContractsState(manifest = twoReleases, selections = emptyMap())
        val key = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Oligarchy)
        assertNull(state.binding(key))
    }

    @Test
    fun binding_returnsNullWhenManifestIsNull() {
        val state = ContractsState(manifest = null, selections = emptyMap())
        assertNull(state.binding(testnetAnarchyKey))
    }

    @Test
    fun binding_returnsNullForMainnetWhenOnlyTestnetContractsPublished() {
        // Today's reality — Mainnet stays disabled until a contract
        // ships.
        val state = ContractsState(manifest = twoReleases, selections = emptyMap())
        assertNull(state.binding(mainnetAnarchyKey))
    }

    // ─── lifecycle ────────────────────────────────────────────────

    @Test
    fun bootstrap_hydratesSelectionsAndCachedManifest() = runTest {
        val store = InMemoryAnchorSelectionStore().apply {
            saveCachedManifest(simpleManifestJson())
            saveSelections(mapOf(testnetAnarchyKey to "v0.0.1"))
        }
        val fetcher = FakeContractsManifestFetcher(
            FakeContractsManifestFetcher.Mode.Failing(IOException("offline")),
        )
        val repo = ContractsRepository(store, fetcher)

        repo.bootstrap()

        val state = repo.snapshots.value
        assertEquals(mapOf(testnetAnarchyKey to "v0.0.1"), state.selections)
        assertEquals(2, state.manifest?.releases?.size)
        assertEquals(0, fetcher.fetchCallCount)
    }

    @Test
    fun start_isIdempotent_acrossMultipleCalls() = runTest {
        val fetcher = FakeContractsManifestFetcher(
            FakeContractsManifestFetcher.Mode.Succeeds(twoReleases),
        )
        val repo = ContractsRepository(InMemoryAnchorSelectionStore(), fetcher)

        repo.start(); repo.start(); repo.start()

        assertEquals(1, fetcher.fetchCallCount)
    }

    @Test
    fun start_swallowsErrorsSilentlyAndKeepsCachedManifest() = runTest {
        val store = InMemoryAnchorSelectionStore().apply {
            saveCachedManifest(simpleManifestJson())
        }
        val fetcher = FakeContractsManifestFetcher(
            FakeContractsManifestFetcher.Mode.Failing(IOException("offline")),
        )
        val repo = ContractsRepository(store, fetcher)
        repo.bootstrap()

        repo.start()  // must not throw

        assertEquals(2, repo.snapshots.value.manifest?.releases?.size)
    }

    @Test
    fun setSelection_persistsAndPushes() = runTest {
        val store = InMemoryAnchorSelectionStore()
        val fetcher = FakeContractsManifestFetcher(
            FakeContractsManifestFetcher.Mode.Succeeds(twoReleases),
        )
        val repo = ContractsRepository(store, fetcher)
        repo.start()

        repo.setSelection(testnetDemocracyKey, "v0.0.2")

        assertEquals("v0.0.2", store.loadSelections()[testnetDemocracyKey])
        assertEquals("v0.0.2", repo.snapshots.value.selections[testnetDemocracyKey])
        // binding resolves through the new selection.
        assertEquals("v0.0.2", repo.snapshots.value.binding(testnetDemocracyKey)?.release)
    }

    @Test
    fun clearSelection_resetsToDefaultLatest() = runTest {
        val store = InMemoryAnchorSelectionStore()
        val fetcher = FakeContractsManifestFetcher(
            FakeContractsManifestFetcher.Mode.Succeeds(twoReleases),
        )
        val repo = ContractsRepository(store, fetcher)
        repo.start()
        repo.setSelection(testnetAnarchyKey, "v0.0.1")
        assertEquals("v0.0.1", repo.snapshots.value.binding(testnetAnarchyKey)?.release)

        repo.clearSelection(testnetAnarchyKey)

        assertTrue("explicit pick must be cleared from store",
            store.loadSelections()[testnetAnarchyKey] == null)
        // Default-to-latest now applies again.
        assertEquals("v0.0.2", repo.snapshots.value.binding(testnetAnarchyKey)?.release)
    }

    // Build a small valid contracts-manifest JSON the in-memory
    // store can decode through ContractsManifest.fromRaw.
    private fun simpleManifestJson(): String = """
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
}
