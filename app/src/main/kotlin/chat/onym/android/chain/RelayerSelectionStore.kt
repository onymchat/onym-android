package chat.onym.android.chain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Persistence seam for the relayer URL selection + the cached
 * known-relayer list. URLs aren't secret material — DataStore
 * Preferences is plenty; encrypted storage stays for identity
 * bytes only.
 *
 * `suspend` everywhere — DataStore reads return [kotlinx.coroutines.flow.Flow]s
 * but we only ever care about the latest snapshot, so the impl
 * collapses every read to `flow.first()`.
 *
 * Mirrors `RelayerSelectionStore` from onym-ios PR #18.
 */
interface RelayerSelectionStore {
    /** Returns `null` if no selection persisted yet. */
    suspend fun loadSelection(): RelayerSelection?

    /** Persist the user's pick. `null` clears it. */
    suspend fun saveSelection(selection: RelayerSelection?)

    /** Last successfully-fetched list, or `[]` if the fetcher has
     *  never succeeded on this install. */
    suspend fun loadCachedKnownRelayers(): List<RelayerEndpoint>

    /** Overwrite the cached list. Empty list is allowed. */
    suspend fun saveCachedKnownRelayers(relayers: List<RelayerEndpoint>)

    /** Test/utility — wipe everything this store has written. */
    suspend fun clear()
}

/**
 * [DataStore]-backed [RelayerSelectionStore]. Stores both the
 * selection and the cached known list as JSON strings under
 * fixed Preferences keys. JSON encoding is permissive on
 * decoding so a future schema-evolution doesn't trip the read.
 *
 * Threading: DataStore handles serialised writes internally; this
 * class is safe to call from multiple coroutines without external
 * synchronisation.
 */
class DataStorePreferencesRelayerSelectionStore(
    private val dataStore: DataStore<Preferences>,
) : RelayerSelectionStore {

    override suspend fun loadSelection(): RelayerSelection? {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_SELECTION] ?: return null
        return try {
            jsonFormat.decodeFromString(SelectionRecord.serializer(), raw).toSelection()
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun saveSelection(selection: RelayerSelection?) {
        dataStore.edit { prefs ->
            if (selection == null) {
                prefs.remove(KEY_SELECTION)
            } else {
                prefs[KEY_SELECTION] = jsonFormat.encodeToString(
                    SelectionRecord.serializer(),
                    SelectionRecord.from(selection),
                )
            }
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

    /** Persistence-only sum type for [RelayerSelection]. We don't
     *  encode the sealed class directly because kotlinx.serialization's
     *  default polymorphic encoding adds a `type` discriminator
     *  that would couple the on-disk format to the Kotlin class
     *  hierarchy — this hand-rolled record is simpler to evolve. */
    @kotlinx.serialization.Serializable
    private data class SelectionRecord(
        val kind: String,           // "known" or "custom"
        val url: String,
        val name: String? = null,   // populated for known
        val network: String? = null, // populated for known
    ) {
        fun toSelection(): RelayerSelection? = when (kind) {
            "known" -> {
                val n = name; val net = network
                if (n != null && net != null) {
                    RelayerSelection.Known(RelayerEndpoint(name = n, url = url, network = net))
                } else null
            }
            "custom" -> RelayerSelection.Custom(url)
            else -> null
        }

        companion object {
            fun from(selection: RelayerSelection): SelectionRecord = when (selection) {
                is RelayerSelection.Known -> SelectionRecord(
                    kind = "known",
                    url = selection.endpoint.url,
                    name = selection.endpoint.name,
                    network = selection.endpoint.network,
                )
                is RelayerSelection.Custom -> SelectionRecord(
                    kind = "custom",
                    url = selection.url,
                )
            }
        }
    }

    private companion object {
        private val KEY_SELECTION = stringPreferencesKey("relayer_selection")
        private val KEY_CACHED = stringPreferencesKey("relayer_cached_known")
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
