package chat.onym.android.support

import chat.onym.android.chain.AnchorSelectionKey
import chat.onym.android.chain.AnchorSelectionStore
import chat.onym.android.chain.ContractsManifest
import chat.onym.android.chain.RawContractsManifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reusable in-memory [AnchorSelectionStore]. Same contract as
 * [chat.onym.android.chain.DataStorePreferencesAnchorSelectionStore],
 * no DataStore / no disk — fast tests of the
 * [chat.onym.android.chain.ContractsRepository] semantics without
 * the persistence-plumbing tax.
 *
 * Caches the manifest as the raw JSON the fetcher returned (same
 * model the production store uses) so the round-trip path matches:
 * load decodes through [ContractsManifest.fromRaw] and drops
 * unknown enum values exactly like production.
 */
class InMemoryAnchorSelectionStore : AnchorSelectionStore {

    private val mutex = Mutex()
    private var selections: Map<AnchorSelectionKey, String> = emptyMap()
    private var cachedManifestJson: String? = null

    override suspend fun loadSelections(): Map<AnchorSelectionKey, String> =
        mutex.withLock { selections }

    override suspend fun saveSelections(selections: Map<AnchorSelectionKey, String>) {
        mutex.withLock { this.selections = selections.toMap() }
    }

    override suspend fun loadCachedManifest(): ContractsManifest? = mutex.withLock {
        val raw = cachedManifestJson ?: return@withLock null
        return try {
            val rawManifest = Json { ignoreUnknownKeys = true }
                .decodeFromString(RawContractsManifest.serializer(), raw)
            ContractsManifest.fromRaw(rawManifest)
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun saveCachedManifest(rawJson: String) {
        mutex.withLock { cachedManifestJson = rawJson }
    }

    override suspend fun clear() {
        mutex.withLock {
            selections = emptyMap()
            cachedManifestJson = null
        }
    }
}
