package app.onym.android.chain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persistence seam for the anchor (contract version) selection +
 * the cached contracts manifest. URLs / contract IDs aren't secret
 * material — DataStore Preferences is plenty.
 *
 * Two pieces of state:
 *
 *  - **Selections**: `Map<AnchorSelectionKey, String>` — release
 *    tag the user picked for each `(network, type)` slot. Empty
 *    map means "no explicit selections; default-to-latest applies
 *    everywhere" — see [ContractsRepository.binding].
 *  - **Cached manifest**: the raw `contracts-manifest.json` body
 *    the fetcher last successfully retrieved. Stored as the raw
 *    JSON string + decoded on load (no double-encoding round
 *    trip through the typed [ContractsManifest]).
 */
interface AnchorSelectionStore {
    suspend fun loadSelections(): Map<AnchorSelectionKey, String>
    suspend fun saveSelections(selections: Map<AnchorSelectionKey, String>)
    suspend fun loadCachedManifest(): ContractsManifest?
    suspend fun saveCachedManifest(rawJson: String)
    suspend fun clear()
}

/**
 * [DataStore]-backed [AnchorSelectionStore]. Selections persist
 * as a single JSON-encoded `Map<String, String>` preference (the
 * map keys are [AnchorSelectionKey.storageKey] strings — DataStore
 * Preferences doesn't natively support `Map<...>`, and a JSON blob
 * is simpler than flattening to N preference keys + cleaning up
 * stale ones on selection delete).
 *
 * Cached manifest persists as the raw JSON body the fetcher
 * received — decoded on load, no re-encode round trip.
 */
class DataStorePreferencesAnchorSelectionStore(
    private val dataStore: DataStore<Preferences>,
) : AnchorSelectionStore {

    override suspend fun loadSelections(): Map<AnchorSelectionKey, String> {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_SELECTIONS] ?: return emptyMap()
        return try {
            jsonFormat
                .decodeFromString(MAP_SERIALIZER, raw)
                .mapNotNull { (k, v) ->
                    AnchorSelectionKey.fromStorageKey(k)?.let { it to v }
                }
                .toMap()
        } catch (_: SerializationException) {
            emptyMap()
        }
    }

    override suspend fun saveSelections(selections: Map<AnchorSelectionKey, String>) {
        dataStore.edit { prefs ->
            if (selections.isEmpty()) {
                prefs.remove(KEY_SELECTIONS)
            } else {
                val stringified = selections.mapKeys { (k, _) -> k.storageKey() }
                prefs[KEY_SELECTIONS] = jsonFormat.encodeToString(MAP_SERIALIZER, stringified)
            }
        }
    }

    override suspend fun loadCachedManifest(): ContractsManifest? {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_CACHED_MANIFEST] ?: return null
        return try {
            val rawManifest = jsonFormat.decodeFromString(RawContractsManifest.serializer(), raw)
            ContractsManifest.fromRaw(rawManifest)
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun saveCachedManifest(rawJson: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CACHED_MANIFEST] = rawJson
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        private val KEY_SELECTIONS = stringPreferencesKey("anchor_selections")
        private val KEY_CACHED_MANIFEST = stringPreferencesKey("contracts_manifest_cached")
        private val jsonFormat = Json { ignoreUnknownKeys = true }
        private val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
