package chat.onym.android.support

import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerSelection
import chat.onym.android.chain.RelayerSelectionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable in-memory [RelayerSelectionStore]. Same contract as
 * [chat.onym.android.chain.DataStorePreferencesRelayerSelectionStore],
 * no DataStore / no disk — ~10× faster per test, lets repository
 * tests focus on behaviour rather than persistence plumbing.
 *
 * Mirrors `InMemoryRelayerSelectionStore` from onym-ios PR #18.
 */
class InMemoryRelayerSelectionStore : RelayerSelectionStore {

    private val mutex = Mutex()
    private var selection: RelayerSelection? = null
    private var cached: List<RelayerEndpoint> = emptyList()

    override suspend fun loadSelection(): RelayerSelection? = mutex.withLock { selection }

    override suspend fun saveSelection(selection: RelayerSelection?) {
        mutex.withLock { this.selection = selection }
    }

    override suspend fun loadCachedKnownRelayers(): List<RelayerEndpoint> =
        mutex.withLock { cached }

    override suspend fun saveCachedKnownRelayers(relayers: List<RelayerEndpoint>) {
        mutex.withLock { this.cached = relayers.toList() }
    }

    override suspend fun clear() {
        mutex.withLock {
            selection = null
            cached = emptyList()
        }
    }
}
