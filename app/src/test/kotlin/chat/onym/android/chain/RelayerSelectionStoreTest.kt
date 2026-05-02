@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package chat.onym.android.chain

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DataStore Preferences round-trip for the selection + cached
 * known-list seam. Uses [PreferenceDataStoreFactory] against a
 * per-test temp file so cases don't bleed into each other.
 *
 * Mirrors `RelayerSelectionStoreTests` from onym-ios PR #18.
 */
class RelayerSelectionStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScope: CoroutineScope
    private lateinit var datastoreScopeJob: Job
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesRelayerSelectionStore

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("relayer-${System.nanoTime()}.preferences_pb")
        // PreferenceDataStoreFactory expects to own the file — passing
        // the just-created empty file is fine; DataStore will write
        // the protobuf header on first write.
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesRelayerSelectionStore(dataStore)
    }

    @After
    fun tearDown() { datastoreScopeJob.cancel() }

    // ─── selection ─────────────────────────────────────────────────

    @Test
    fun loadSelection_returnsNullOnFreshStore() = runTest {
        assertNull(store.loadSelection())
    }

    @Test
    fun saveAndLoad_known() = runTest {
        val ep = RelayerEndpoint("Onym Testnet", "https://relayer-testnet.onym.chat", "testnet")
        store.saveSelection(RelayerSelection.Known(ep))
        val loaded = store.loadSelection() as RelayerSelection.Known
        assertEquals(ep, loaded.endpoint)
    }

    @Test
    fun saveAndLoad_custom() = runTest {
        store.saveSelection(RelayerSelection.Custom("https://localhost:8080"))
        val loaded = store.loadSelection() as RelayerSelection.Custom
        assertEquals("https://localhost:8080", loaded.url)
    }

    @Test
    fun saveSelection_nullClears() = runTest {
        store.saveSelection(RelayerSelection.Custom("https://x.example"))
        store.saveSelection(null)
        assertNull(store.loadSelection())
    }

    // ─── cached known list ─────────────────────────────────────────

    @Test
    fun loadCachedKnownRelayers_returnsEmptyOnFreshStore() = runTest {
        assertTrue(store.loadCachedKnownRelayers().isEmpty())
    }

    @Test
    fun saveAndLoad_cachedKnownRelayers() = runTest {
        val list = listOf(
            RelayerEndpoint("A", "https://a.example", "testnet"),
            RelayerEndpoint("B", "https://b.example", "public"),
        )
        store.saveCachedKnownRelayers(list)
        assertEquals(list, store.loadCachedKnownRelayers())
    }

    @Test
    fun saveCachedKnownRelayers_overwritesPreviousList() = runTest {
        store.saveCachedKnownRelayers(listOf(RelayerEndpoint("A", "https://a.example", "testnet")))
        val newList = listOf(RelayerEndpoint("B", "https://b.example", "public"))
        store.saveCachedKnownRelayers(newList)
        assertEquals(newList, store.loadCachedKnownRelayers())
    }
}
