package app.onym.android.discovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistence seam for the Discovery source configuration + the last
 * verified, source-attributed entry list. Keys and digests aren't
 * secret material — DataStore Preferences is plenty (same reasoning
 * as :chain's `RelayerSelectionStore`).
 */
interface DiscoveryStore {
    /** Returns [DiscoverySourcesConfiguration.empty] if nothing's
     *  been persisted yet (cold start) or the blob was unreadable. */
    suspend fun loadConfiguration(): DiscoverySourcesConfiguration

    /** Persist the configuration. Empty configurations are saved
     *  as-is (the user can remove every source; direct import keeps
     *  working — absence from every catalog never blocks use). */
    suspend fun saveConfiguration(configuration: DiscoverySourcesConfiguration)

    /** Last verified entry list, or `[]` if no refresh has ever
     *  succeeded on this install. */
    suspend fun loadCachedEntries(): List<AttributedCatalogEntry>

    /** Overwrite the cached entry list. Empty list is allowed. */
    suspend fun saveCachedEntries(entries: List<AttributedCatalogEntry>)

    /** Test/utility — wipe everything this store has written. */
    suspend fun clear()
}

/**
 * [DataStore]-backed [DiscoveryStore]. Stores the configuration +
 * cached entries as JSON strings under fixed `discovery.` preference
 * keys.
 *
 * Threading: DataStore handles serialised writes internally; safe to
 * call from multiple coroutines without external sync.
 */
class DataStorePreferencesDiscoveryStore(
    private val dataStore: DataStore<Preferences>,
) : DiscoveryStore {

    override suspend fun loadConfiguration(): DiscoverySourcesConfiguration {
        val raw = dataStore.data.first()[KEY_CONFIGURATION]
            ?: return DiscoverySourcesConfiguration.empty
        // Corrupt blob → fall back to .empty so bootstrap can re-seed
        // the shipped defaults (the user lost their configuration to
        // data corruption; re-seeding is a reasonable recovery).
        return try {
            jsonFormat.decodeFromString(DiscoverySourcesConfiguration.serializer(), raw)
        } catch (_: SerializationException) {
            DiscoverySourcesConfiguration.empty
        }
    }

    override suspend fun saveConfiguration(configuration: DiscoverySourcesConfiguration) {
        dataStore.edit { prefs ->
            prefs[KEY_CONFIGURATION] = jsonFormat.encodeToString(
                DiscoverySourcesConfiguration.serializer(),
                configuration,
            )
        }
    }

    override suspend fun loadCachedEntries(): List<AttributedCatalogEntry> {
        val raw = dataStore.data.first()[KEY_CACHED_ENTRIES] ?: return emptyList()
        return try {
            jsonFormat.decodeFromString(entriesSerializer, raw)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    override suspend fun saveCachedEntries(entries: List<AttributedCatalogEntry>) {
        dataStore.edit { prefs ->
            prefs[KEY_CACHED_ENTRIES] = jsonFormat.encodeToString(entriesSerializer, entries)
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_CONFIGURATION)
            prefs.remove(KEY_CACHED_ENTRIES)
        }
    }

    private companion object {
        private val KEY_CONFIGURATION = stringPreferencesKey("discovery.sources_configuration")
        private val KEY_CACHED_ENTRIES = stringPreferencesKey("discovery.cached_entries")

        private val entriesSerializer = ListSerializer(AttributedCatalogEntry.serializer())

        /** Tolerant on read — persisted blobs are our own writes, and
         *  forward-compat across app versions beats strictness here
         *  (wire-document strictness lives in [DiscoveryJson.strict]). */
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
