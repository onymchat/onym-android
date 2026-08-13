@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.discovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
 * [DataStorePreferencesDiscoveryStore] round-trips on a real JVM
 * `PreferenceDataStoreFactory` backed by a per-test temp file — same
 * pattern as :chain's `RelayerSelectionStoreTest`.
 */
class DiscoverySourcesStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesDiscoveryStore

    private val onymSource = DiscoverySource(
        url = "https://discovery.onym.app/manifest.json",
        label = "Onym Discovery",
        providerId = "onym:component:onym-discovery",
        pinnedOperatorKeyHex = DiscoveryFixtures.OPERATOR_KEY_HEX,
        acceptedCatalogs = mapOf(
            "public-all-seats" to AcceptedCatalogSnapshot(
                digest = "sha256:" + "aa".repeat(32),
                sequence = 3,
                acceptedAt = "2026-08-14T00:00:00Z",
            ),
        ),
    )

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("discovery-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesDiscoveryStore(dataStore)
    }

    @After
    fun tearDown() { datastoreScopeJob.cancel() }

    @Test
    fun loadConfiguration_returnsEmptyOnFreshStore() = runTest {
        // Cold-fresh store returns .empty (hasUserInteracted=false)
        // so bootstrap may seed the shipped default sources.
        assertEquals(DiscoverySourcesConfiguration.empty, store.loadConfiguration())
    }

    @Test
    fun saveAndLoad_configurationRoundTrips() = runTest {
        val cfg = DiscoverySourcesConfiguration(
            sources = listOf(onymSource),
            removedDefaultProviderIds = setOf("onym:component:removed-provider"),
            hasUserInteracted = true,
        )
        store.saveConfiguration(cfg)
        assertEquals(cfg, store.loadConfiguration())
    }

    @Test
    fun saveConfiguration_overwritesPrevious() = runTest {
        store.saveConfiguration(DiscoverySourcesConfiguration(sources = listOf(onymSource)))
        val updated = DiscoverySourcesConfiguration(
            sources = emptyList(),
            removedDefaultProviderIds = setOf(onymSource.providerId),
        )
        store.saveConfiguration(updated)
        assertEquals(updated, store.loadConfiguration())
    }

    @Test
    fun loadConfiguration_freshStore_doesNotWrite() = runTest {
        store.loadConfiguration()
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("discovery.sources_configuration")])
    }

    @Test
    fun keys_liveUnderTheDiscoveryPrefix() = runTest {
        store.saveConfiguration(DiscoverySourcesConfiguration(sources = listOf(onymSource)))
        store.saveCachedEntries(emptyList())
        val prefs = dataStore.data.first()
        assertNotNull(prefs[stringPreferencesKey("discovery.sources_configuration")])
        assertNotNull(prefs[stringPreferencesKey("discovery.cached_entries")])
    }

    @Test
    fun corruptConfigurationBlob_fallsBackToEmpty() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("discovery.sources_configuration")] = "not json {{"
        }
        assertEquals(DiscoverySourcesConfiguration.empty, store.loadConfiguration())
    }

    @Test
    fun saveAndLoad_cachedEntriesRoundTrip() = runTest {
        val entry = AttributedCatalogEntry(
            entry = CatalogEntry(
                componentId = "onym:component:onym-courier",
                seatType = "transport.message",
                manifest = ManifestRef(
                    uri = "https://discovery.onym.app/manifests/onym-courier.json",
                    digest = "sha256:" + "bb".repeat(32),
                ),
                operator = "onym:key:" + DiscoveryFixtures.OPERATOR_KEY_HEX,
                profiles = listOf("onym:message-implementation:nostr-courier-v1"),
                listedAt = "2026-08-13T00:00:00Z",
                relationship = EntryRelationship.CommonOwner,
                placement = "policy-ranked",
            ),
            attribution = SourceAttribution(
                providerId = "onym:component:onym-discovery",
                sourceLabel = "Onym Discovery",
                catalogId = "public-all-seats",
                snapshotDigest = "sha256:" + "aa".repeat(32),
                relationship = EntryRelationship.CommonOwner,
                placement = "policy-ranked",
            ),
        )
        store.saveCachedEntries(listOf(entry))
        assertEquals(listOf(entry), store.loadCachedEntries())
    }

    @Test
    fun loadCachedEntries_returnsEmptyOnFreshStore() = runTest {
        assertTrue(store.loadCachedEntries().isEmpty())
    }

    @Test
    fun clear_wipesBothKeys() = runTest {
        store.saveConfiguration(DiscoverySourcesConfiguration(sources = listOf(onymSource)))
        store.saveCachedEntries(emptyList())
        store.clear()
        assertEquals(DiscoverySourcesConfiguration.empty, store.loadConfiguration())
        assertTrue(store.loadCachedEntries().isEmpty())
    }
}
