package chat.onym.android.support

import chat.onym.android.chain.RelayerConfiguration
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerSelectionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable in-memory [RelayerSelectionStore]. Same contract as
 * [chat.onym.android.chain.DataStorePreferencesRelayerSelectionStore],
 * no DataStore / no disk — fast tests of repository semantics
 * without persistence-plumbing tax.
 *
 * Updated to the multi-endpoint shape from PR #20.
 */
class InMemoryRelayerSelectionStore : RelayerSelectionStore {

    private val mutex = Mutex()
    private var configuration: RelayerConfiguration? = null
    private var cached: List<RelayerEndpoint> = emptyList()

    override suspend fun loadConfiguration(): RelayerConfiguration =
        mutex.withLock { configuration ?: RelayerConfiguration.empty }

    override suspend fun saveConfiguration(configuration: RelayerConfiguration) {
        mutex.withLock { this.configuration = configuration }
    }

    override suspend fun loadCachedKnownRelayers(): List<RelayerEndpoint> =
        mutex.withLock { cached }

    override suspend fun saveCachedKnownRelayers(relayers: List<RelayerEndpoint>) {
        mutex.withLock { this.cached = relayers.toList() }
    }

    override suspend fun clear() {
        mutex.withLock {
            configuration = null
            cached = emptyList()
        }
    }
}
