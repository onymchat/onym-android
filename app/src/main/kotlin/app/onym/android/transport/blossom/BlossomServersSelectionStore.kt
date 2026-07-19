package app.onym.android.transport.blossom

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Persistence seam for [BlossomServersConfiguration]. Same DataStore
 * Preferences pattern as the Nostr relays store; the JSON blob lives
 * under a single key so the schema can grow without touching the
 * storage layer.
 *
 * Mirrors `NostrRelaysSelectionStore` and onym-ios
 * `BlossomServersSelectionStore.swift`.
 */
interface BlossomServersSelectionStore {
    suspend fun load(): BlossomServersConfiguration
    suspend fun save(config: BlossomServersConfiguration)
}

/** Production [BlossomServersSelectionStore] backed by DataStore. */
class DataStoreBlossomServersSelectionStore(
    private val dataStore: DataStore<Preferences>,
) : BlossomServersSelectionStore {

    override suspend fun load(): BlossomServersConfiguration {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_CONFIG] ?: return BlossomServersConfiguration.empty
        return try {
            jsonFormat.decodeFromString(BlossomServersConfiguration.serializer(), raw)
        } catch (_: Throwable) {
            BlossomServersConfiguration.empty
        }
    }

    override suspend fun save(config: BlossomServersConfiguration) {
        val raw = jsonFormat.encodeToString(BlossomServersConfiguration.serializer(), config)
        dataStore.edit { it[KEY_CONFIG] = raw }
    }

    private companion object {
        private val KEY_CONFIG = stringPreferencesKey("blossom.servers.config.v1")
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}

/** In-memory [BlossomServersSelectionStore] for tests. */
class InMemoryBlossomServersSelectionStore(
    initial: BlossomServersConfiguration = BlossomServersConfiguration.empty,
) : BlossomServersSelectionStore {
    @Volatile
    private var snapshot: BlossomServersConfiguration = initial

    override suspend fun load(): BlossomServersConfiguration = snapshot

    override suspend fun save(config: BlossomServersConfiguration) {
        snapshot = config
    }
}
