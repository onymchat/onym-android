package chat.onym.android.chain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Persistence seam for the multi-endpoint relayer configuration +
 * the cached known-relayer list. URLs aren't secret material —
 * DataStore Preferences is plenty; identity bytes continue to live
 * in EncryptedSharedPreferences.
 *
 * `loadConfiguration` runs migration from the PR #17 single-
 * selection format on first call; the legacy preference is
 * removed afterwards so subsequent calls just read the new key.
 *
 * Mirrors `RelayerSelectionStore` from onym-ios PR #20.
 */
interface RelayerSelectionStore {
    /** Returns an empty configuration if nothing's been persisted
     *  yet (cold start) or if the legacy blob was unreadable. */
    suspend fun loadConfiguration(): RelayerConfiguration

    /** Persist the configuration. Empty configurations are saved
     *  as-is (the user can clear all endpoints; the chain client
     *  refuses to publish until they re-add at least one). */
    suspend fun saveConfiguration(configuration: RelayerConfiguration)

    /** Last successfully-fetched list, or `[]` if the fetcher has
     *  never succeeded on this install. */
    suspend fun loadCachedKnownRelayers(): List<RelayerEndpoint>

    /** Overwrite the cached list. Empty list is allowed. */
    suspend fun saveCachedKnownRelayers(relayers: List<RelayerEndpoint>)

    /** Test/utility — wipe everything this store has written. */
    suspend fun clear()
}

/**
 * [DataStore]-backed [RelayerSelectionStore]. Stores the
 * configuration + cached list as JSON strings under fixed
 * preference keys.
 *
 * Migration: on the first [loadConfiguration] call after upgrading
 * from PR #17, the legacy `relayer_selection` preference is read,
 * mapped into a single-endpoint [RelayerConfiguration] (primary +
 * the one endpoint), persisted under the new
 * `relayer_configuration` key, and the legacy preference is
 * removed. Subsequent calls just read the new key — migration
 * runs once per install and the test pins this property.
 *
 * Threading: DataStore handles serialised writes internally; safe
 * to call from multiple coroutines without external sync.
 */
class DataStorePreferencesRelayerSelectionStore(
    private val dataStore: DataStore<Preferences>,
) : RelayerSelectionStore {

    override suspend fun loadConfiguration(): RelayerConfiguration {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_CONFIGURATION]
        if (raw != null) {
            // New-key path. Once migration has run we never look at
            // the legacy key again — migrated installs end up here.
            return try {
                jsonFormat.decodeFromString(RelayerConfiguration.serializer(), raw)
            } catch (_: SerializationException) {
                RelayerConfiguration()
            }
        }
        // Cold install OR pre-migration. Try the legacy key once.
        val legacy = prefs[LEGACY_KEY_SELECTION]
            ?: return RelayerConfiguration()  // truly cold; no writes
        return migrateLegacySelection(legacy)
    }

    override suspend fun saveConfiguration(configuration: RelayerConfiguration) {
        dataStore.edit { prefs ->
            prefs[KEY_CONFIGURATION] = jsonFormat.encodeToString(
                RelayerConfiguration.serializer(),
                configuration,
            )
            // Defence in depth — if a save races a load that
            // hadn't yet migrated, drop the legacy key here too.
            prefs.remove(LEGACY_KEY_SELECTION)
        }
    }

    override suspend fun loadCachedKnownRelayers(): List<RelayerEndpoint> {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_CACHED] ?: return emptyList()
        return try {
            jsonFormat.decodeFromString(KnownRelayersDocument.serializer(), raw).relayers
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    override suspend fun saveCachedKnownRelayers(relayers: List<RelayerEndpoint>) {
        dataStore.edit { prefs ->
            prefs[KEY_CACHED] = jsonFormat.encodeToString(
                KnownRelayersDocument.serializer(),
                KnownRelayersDocument(version = 1, relayers = relayers),
            )
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /** Legacy `RelayerSelection` (PR #17) → multi-endpoint
     *  configuration. Decode, build, persist under the new key,
     *  drop the legacy key — all atomic in one `edit { }`. */
    private suspend fun migrateLegacySelection(legacyRaw: String): RelayerConfiguration {
        val migrated = try {
            val record = jsonFormat.decodeFromString(LegacySelectionRecord.serializer(), legacyRaw)
            val endpoint = when (record.kind) {
                "known" -> {
                    val n = record.name; val net = record.network
                    if (n != null && net != null) {
                        RelayerEndpoint(name = n, url = record.url, network = net)
                    } else null
                }
                "custom" -> RelayerEndpoint.custom(record.url)
                else -> null
            }
            if (endpoint != null) {
                RelayerConfiguration(
                    endpoints = listOf(endpoint),
                    primaryUrl = endpoint.url,
                    strategy = RelayerStrategy.PRIMARY,
                )
            } else {
                RelayerConfiguration()  // unknown kind; drop
            }
        } catch (_: SerializationException) {
            RelayerConfiguration()  // corrupt blob; drop
        }
        // Persist the migrated configuration AND remove the legacy
        // key in a single atomic edit so a subsequent crash + relaunch
        // doesn't re-trigger the migration path.
        dataStore.edit { prefs ->
            // Only write the new key if migration produced something
            // — but always drop the legacy key (corrupt blob also
            // gets wiped so we don't keep retrying it).
            if (migrated.endpoints.isNotEmpty()) {
                prefs[KEY_CONFIGURATION] = jsonFormat.encodeToString(
                    RelayerConfiguration.serializer(),
                    migrated,
                )
            }
            prefs.remove(LEGACY_KEY_SELECTION)
        }
        return migrated
    }

    /** Mirrors the on-disk shape PR #17 wrote. Decoded + dropped
     *  during one-time migration; never written by this version. */
    @kotlinx.serialization.Serializable
    private data class LegacySelectionRecord(
        val kind: String,           // "known" or "custom"
        val url: String,
        val name: String? = null,
        val network: String? = null,
    )

    private companion object {
        /** New PR #20 multi-endpoint key. */
        private val KEY_CONFIGURATION = stringPreferencesKey("relayer_configuration")

        /** Cached known-list key — unchanged across PR #17 → PR #20. */
        private val KEY_CACHED = stringPreferencesKey("relayer_cached_known")

        /** PR #17 single-selection key. Read once during migration,
         *  then removed. Never written by this version. */
        private val LEGACY_KEY_SELECTION = stringPreferencesKey("relayer_selection")

        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
