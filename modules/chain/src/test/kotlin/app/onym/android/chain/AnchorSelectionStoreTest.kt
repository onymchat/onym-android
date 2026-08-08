@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.chain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DataStore round-trip for the anchor selections + cached manifest
 * seam. Same `PreferenceDataStoreFactory` + `TemporaryFolder`
 * pattern as [RelayerSelectionStoreTest].
 */
class AnchorSelectionStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesAnchorSelectionStore

    private val k1 = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Anarchy)
    private val k2 = AnchorSelectionKey(ContractNetwork.Testnet, GovernanceType.Democracy)

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("anchors-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesAnchorSelectionStore(dataStore)
    }

    @After
    fun tearDown() { datastoreScopeJob.cancel() }

    // ─── selections ────────────────────────────────────────────────

    @Test
    fun loadSelections_returnsEmptyOnFreshStore() = runTest {
        assertTrue(store.loadSelections().isEmpty())
    }

    @Test
    fun saveAndLoad_singleSelection() = runTest {
        store.saveSelections(mapOf(k1 to "v0.0.2"))
        val loaded = store.loadSelections()
        assertEquals(mapOf(k1 to "v0.0.2"), loaded)
    }

    @Test
    fun saveAndLoad_multipleSelections() = runTest {
        store.saveSelections(mapOf(k1 to "v0.0.2", k2 to "v0.0.1"))
        assertEquals(mapOf(k1 to "v0.0.2", k2 to "v0.0.1"), store.loadSelections())
    }

    @Test
    fun saveSelections_overwritesPreviousMap() = runTest {
        store.saveSelections(mapOf(k1 to "v0.0.1"))
        store.saveSelections(mapOf(k1 to "v0.0.2", k2 to "v0.0.2"))
        assertEquals(mapOf(k1 to "v0.0.2", k2 to "v0.0.2"), store.loadSelections())
    }

    @Test
    fun saveSelections_emptyClearsAll() = runTest {
        store.saveSelections(mapOf(k1 to "v0.0.2"))
        store.saveSelections(emptyMap())
        assertTrue(store.loadSelections().isEmpty())
    }

    // ─── cached manifest ───────────────────────────────────────────

    @Test
    fun loadCachedManifest_returnsNullOnFreshStore() = runTest {
        assertNull(store.loadCachedManifest())
    }

    @Test
    fun saveAndLoad_cachedManifest_decodesUnknownDropped() = runTest {
        // Includes an unknown network ("futurenet") that must be
        // dropped on load through ContractsManifest.fromRaw.
        val raw = """
            {
              "version": 1,
              "releases": [
                {
                  "release": "v0.0.2",
                  "publishedAt": "2026-05-01T15:29:00Z",
                  "contracts": [
                    { "network": "testnet",   "type": "anarchy", "id": "C-A-2" },
                    { "network": "futurenet", "type": "anarchy", "id": "C-A-FUTURE" }
                  ]
                }
              ]
            }
        """.trimIndent()
        store.saveCachedManifest(raw)
        val loaded = store.loadCachedManifest()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.releases.single().contracts.size)
        assertEquals(ContractNetwork.Testnet, loaded.releases.single().contracts.single().network)
    }
}
