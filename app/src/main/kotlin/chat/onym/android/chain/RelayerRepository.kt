package chat.onym.android.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Owns the in-memory view of the multi-endpoint relayer
 * configuration plus the corresponding writes to the persistence
 * + network seams. Same shape as [chat.onym.android.identity.IdentityRepository] —
 * Mutex + StateFlow.
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [store] + [fetcher]. No transport, no OnymSDK,
 *    no Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [addEndpoint] /
 *    [removeEndpoint] / [setPrimary] / [setStrategy] / [start] /
 *    [refresh] / [bootstrap] are the suspend mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free
 *    off the [StateFlow].
 *
 * Mirrors `RelayerRepository` from onym-ios PR #20.
 */
class RelayerRepository(
    private val store: RelayerSelectionStore,
    private val fetcher: KnownRelayersFetcher,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(
        RelayerState(
            knownRelayers = emptyList(),
            configuration = RelayerConfiguration(),
        )
    )

    val snapshots: StateFlow<RelayerState> = _snapshots.asStateFlow()

    private var startInvoked = false

    /** Hydrate from disk — cached known list + persisted multi-
     *  endpoint configuration (or migrate from the PR #17 single-
     *  selection blob). Idempotent. */
    suspend fun bootstrap() = mutex.withLock {
        val cached = store.loadCachedKnownRelayers()
        val config = store.loadConfiguration()
        _snapshots.value = RelayerState(knownRelayers = cached, configuration = config)
    }

    /** Fire-and-forget the GitHub-Releases fetch. Idempotent. */
    suspend fun start() = mutex.withLock {
        if (startInvoked) return@withLock
        startInvoked = true
        runCatching { fetcher.fetch() }.onSuccess { fresh ->
            store.saveCachedKnownRelayers(fresh)
            _snapshots.value = _snapshots.value.copy(knownRelayers = fresh)
        }
    }

    /** User-initiated refresh; failures propagate. */
    suspend fun refresh() {
        val fresh = fetcher.fetch()
        mutex.withLock {
            store.saveCachedKnownRelayers(fresh)
            _snapshots.value = _snapshots.value.copy(knownRelayers = fresh)
        }
    }

    // ─── list-shaped mutators ─────────────────────────────────────

    /** Add (or replace metadata for) an endpoint, keyed by URL. If
     *  the URL is already configured, the metadata (name, network)
     *  is updated in place — useful when the same URL is added
     *  first as a custom and then "rediscovered" in the published
     *  list, or vice versa. Insertion order preserved for new
     *  endpoints. */
    suspend fun addEndpoint(endpoint: RelayerEndpoint) = mutex.withLock {
        val current = _snapshots.value.configuration
        val existingIdx = current.endpoints.indexOfFirst { it.url == endpoint.url }
        val newEndpoints = if (existingIdx >= 0) {
            current.endpoints.toMutableList().also { it[existingIdx] = endpoint }
        } else {
            current.endpoints + endpoint
        }
        applyConfigurationLocked(current.copy(endpoints = newEndpoints))
    }

    /** Remove [url] from the configuration. If [url] was the
     *  primary, the primary marker is auto-cleared so [selectUrl]
     *  under [RelayerStrategy.PRIMARY] falls back to the new first
     *  endpoint. No-op for unknown URLs. */
    suspend fun removeEndpoint(url: String) = mutex.withLock {
        val current = _snapshots.value.configuration
        if (current.endpoints.none { it.url == url }) return@withLock
        val newEndpoints = current.endpoints.filterNot { it.url == url }
        val newPrimary = if (current.primaryUrl == url) null else current.primaryUrl
        applyConfigurationLocked(
            current.copy(endpoints = newEndpoints, primaryUrl = newPrimary)
        )
    }

    /** Mark the endpoint with [url] as the primary. No-op if [url]
     *  isn't in the configured endpoints — UI shouldn't expose this
     *  intent for unconfigured URLs, but the no-op tolerates a
     *  race between add/remove and a stale primary tap. */
    suspend fun setPrimary(url: String) = mutex.withLock {
        val current = _snapshots.value.configuration
        if (current.endpoints.none { it.url == url }) return@withLock
        applyConfigurationLocked(current.copy(primaryUrl = url))
    }

    /** Switch between [RelayerStrategy.PRIMARY] and
     *  [RelayerStrategy.RANDOM]. */
    suspend fun setStrategy(strategy: RelayerStrategy) = mutex.withLock {
        val current = _snapshots.value.configuration
        applyConfigurationLocked(current.copy(strategy = strategy))
    }

    // ─── resolution ───────────────────────────────────────────────

    /** Resolve the URL to use for a single chain request. Pure
     *  pass-through to [RelayerConfiguration.selectUrl]; exposed
     *  here so callers don't have to reach into `snapshots.value`
     *  + the mutex doesn't gate reads (the underlying [StateFlow]
     *  is lock-free). */
    fun selectUrl(random: Random = Random.Default): String? =
        _snapshots.value.configuration.selectUrl(random)

    private suspend fun applyConfigurationLocked(updated: RelayerConfiguration) {
        store.saveConfiguration(updated)
        _snapshots.value = _snapshots.value.copy(configuration = updated)
    }
}
