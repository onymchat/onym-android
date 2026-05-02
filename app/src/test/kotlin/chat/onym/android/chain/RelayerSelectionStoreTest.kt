@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package chat.onym.android.chain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * DataStore round-trip + the load-bearing PR #17 → PR #20 migration.
 * Same `PreferenceDataStoreFactory` + `TemporaryFolder` pattern as
 * the PR #17 store test.
 */
class RelayerSelectionStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesRelayerSelectionStore

    private val testnet = RelayerEndpoint("Onym Testnet", "https://relayer-testnet.onym.chat", "testnet")

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("relayer-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesRelayerSelectionStore(dataStore)
    }

    @After
    fun tearDown() { datastoreScopeJob.cancel() }

    // ─── new-format round-trip ────────────────────────────────────

    @Test
    fun loadConfiguration_returnsEmptyOnFreshStore() = runTest {
        assertEquals(RelayerConfiguration(), store.loadConfiguration())
    }

    @Test
    fun saveAndLoad_multiEndpointConfiguration() = runTest {
        val cfg = RelayerConfiguration(
            endpoints = listOf(
                testnet,
                RelayerEndpoint.custom("https://localhost:8080"),
            ),
            primaryUrl = testnet.url,
            strategy = RelayerStrategy.RANDOM,
        )
        store.saveConfiguration(cfg)
        assertEquals(cfg, store.loadConfiguration())
    }

    @Test
    fun saveConfiguration_overwritesPrevious() = runTest {
        store.saveConfiguration(
            RelayerConfiguration(endpoints = listOf(testnet), primaryUrl = testnet.url)
        )
        val updated = RelayerConfiguration()
        store.saveConfiguration(updated)
        assertEquals(updated, store.loadConfiguration())
    }

    @Test
    fun loadConfiguration_freshStore_doesNotWrite() = runTest {
        // Cold-start property: a load on an empty store must not
        // touch DataStore. Asserts via the absence of the new-config
        // key after the load.
        store.loadConfiguration()
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("relayer_configuration")])
        assertNull(prefs[stringPreferencesKey("relayer_selection")])
    }

    // ─── migration paths ──────────────────────────────────────────

    @Test
    fun migration_legacyKnown_buildsSingleEndpointPrimaryConfiguration() = runTest {
        writeLegacy("""{"kind":"known","url":"https://relayer-testnet.onym.chat","name":"Onym Testnet","network":"testnet"}""")

        val migrated = store.loadConfiguration()
        assertEquals(1, migrated.endpoints.size)
        assertEquals(testnet, migrated.endpoints.single())
        assertEquals(testnet.url, migrated.primaryUrl)
        assertEquals(RelayerStrategy.PRIMARY, migrated.strategy)
    }

    @Test
    fun migration_legacyCustom_synthesizesCustomEndpoint() = runTest {
        writeLegacy("""{"kind":"custom","url":"https://localhost:8080"}""")

        val migrated = store.loadConfiguration()
        assertEquals(1, migrated.endpoints.size)
        val ep = migrated.endpoints.single()
        assertEquals("custom", ep.network)
        assertEquals("https://localhost:8080", ep.url)
        assertEquals("localhost", ep.name)
        assertEquals(ep.url, migrated.primaryUrl)
    }

    @Test
    fun migration_corruptLegacyBlob_dropsKeyAndReturnsEmpty() = runTest {
        writeLegacy("not really json {{")

        val migrated = store.loadConfiguration()
        assertEquals(RelayerConfiguration(), migrated)
        // Legacy key cleared so subsequent loads don't keep retrying.
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("relayer_selection")])
    }

    @Test
    fun migration_runsOnlyOnce_legacyKeyRemovedAfterMigration() = runTest {
        writeLegacy("""{"kind":"known","url":"https://relayer-testnet.onym.chat","name":"Onym Testnet","network":"testnet"}""")

        // First load triggers migration.
        val migrated1 = store.loadConfiguration()
        assertEquals(1, migrated1.endpoints.size)

        // Legacy key is gone.
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("relayer_selection")])
        assertTrue(
            "new key must be present after migration",
            prefs[stringPreferencesKey("relayer_configuration")] != null,
        )

        // Second load reads the new key only — same value.
        val migrated2 = store.loadConfiguration()
        assertEquals(migrated1, migrated2)
    }

    // ─── cached known list (unchanged from PR #17) ────────────────

    @Test
    fun saveAndLoad_cachedKnownRelayers() = runTest {
        val list = listOf(testnet)
        store.saveCachedKnownRelayers(list)
        assertEquals(list, store.loadCachedKnownRelayers())
    }

    @Test
    fun loadCachedKnownRelayers_returnsEmptyOnFreshStore() = runTest {
        assertTrue(store.loadCachedKnownRelayers().isEmpty())
    }

    private suspend fun writeLegacy(blob: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("relayer_selection")] = blob }
    }
}
