package app.onym.android.transport.nostr

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Persistence seam for [NostrRelaysConfiguration]. Same DataStore
 * Preferences pattern as the relayer + contracts stores; the JSON
 * blob lives under a single key so the schema can grow without
 * touching the storage layer.
 *
 * Mirrors `NostrRelaysSelectionStore.swift` from onym-ios PR #87.
 */
interface NostrRelaysSelectionStore {
    suspend fun load(): NostrRelaysConfiguration
    suspend fun save(config: NostrRelaysConfiguration)
}

/** Production [NostrRelaysSelectionStore] backed by DataStore. */
class DataStoreNostrRelaysSelectionStore(
    private val dataStore: DataStore<Preferences>,
) : NostrRelaysSelectionStore {

    override suspend fun load(): NostrRelaysConfiguration {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_CONFIG] ?: return NostrRelaysConfiguration.empty
        return try {
            jsonFormat.decodeFromString(NostrRelaysConfiguration.serializer(), raw)
        } catch (_: Throwable) {
            NostrRelaysConfiguration.empty
        }
    }

    override suspend fun save(config: NostrRelaysConfiguration) {
        val raw = jsonFormat.encodeToString(NostrRelaysConfiguration.serializer(), config)
        dataStore.edit { it[KEY_CONFIG] = raw }
    }

    private companion object {
        private val KEY_CONFIG = stringPreferencesKey("nostr.relays.config.v1")
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}

/** In-memory [NostrRelaysSelectionStore] for tests. */
class InMemoryNostrRelaysSelectionStore(
    initial: NostrRelaysConfiguration = NostrRelaysConfiguration.empty,
) : NostrRelaysSelectionStore {
    @Volatile
    private var snapshot: NostrRelaysConfiguration = initial

    override suspend fun load(): NostrRelaysConfiguration = snapshot

    override suspend fun save(config: NostrRelaysConfiguration) {
        snapshot = config
    }
}
