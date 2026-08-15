package app.onym.android.support

import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.DiscoverySourcesConfiguration
import app.onym.android.discovery.DiscoveryStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable in-memory [DiscoveryStore]. Same contract as
 * [app.onym.android.discovery.DataStorePreferencesDiscoveryStore],
 * no DataStore / no disk — fast tests of repository semantics
 * without persistence-plumbing tax.
 */
class InMemoryDiscoveryStore : DiscoveryStore {

    private val mutex = Mutex()
    private var configuration: DiscoverySourcesConfiguration? = null
    private var cachedEntries: List<AttributedCatalogEntry> = emptyList()

    override suspend fun loadConfiguration(): DiscoverySourcesConfiguration =
        mutex.withLock { configuration ?: DiscoverySourcesConfiguration.empty }

    override suspend fun saveConfiguration(configuration: DiscoverySourcesConfiguration) {
        mutex.withLock { this.configuration = configuration }
    }

    override suspend fun loadCachedEntries(): List<AttributedCatalogEntry> =
        mutex.withLock { cachedEntries }

    override suspend fun saveCachedEntries(entries: List<AttributedCatalogEntry>) {
        mutex.withLock { cachedEntries = entries.toList() }
    }

    override suspend fun clear() {
        mutex.withLock {
            configuration = null
            cachedEntries = emptyList()
        }
    }
}
