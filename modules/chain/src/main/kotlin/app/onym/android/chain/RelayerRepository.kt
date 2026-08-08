package app.onym.android.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Owns the in-memory view of the multi-endpoint relayer
 * configuration plus the corresponding writes to the persistence
 * + network seams. Same shape as [app.onym.android.identity.IdentityRepository] —
 * Mutex + StateFlow.
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [store] + [fetcher] + [errorMessageResolver].
 *    No transport, no OnymSDK, no Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [addEndpoint] /
 *    [removeEndpoint] / [setPrimary] / [setStrategy] / [start] /
 *    [refresh] / [bootstrap] are the suspend mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free
 *    off the [StateFlow]. The mutex is **not** held across
 *    [fetcher.fetch] — the network call runs on its own
 *    coroutine continuation.
 *
 * Mirrors `RelayerRepository` from onym-ios PR #20 / PR #23.
 */
class RelayerRepository(
    private val store: RelayerSelectionStore,
    private val fetcher: KnownRelayersFetcher,
    /** Maps a fetch failure to a user-facing localised string. The
     *  default returns the throwable's message — production wires a
     *  resource-backed implementation through `OnymApplication`;
     *  tests typically pass `{ it.message ?: "" }` or a controlled
     *  fixture. */
    private val errorMessageResolver: (Throwable) -> String = { it.message ?: "" },
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(RelayerState.empty)

    val snapshots: StateFlow<RelayerState> = _snapshots.asStateFlow()

    private var startInvoked = false

    /** Hydrate from disk — cached known list + persisted multi-
     *  endpoint configuration (or migrate from the PR #17 single-
     *  selection blob). Idempotent. Preserves the in-memory
     *  [RelayerFetchStatus] (which is `Idle` until the first
     *  [start] / [refresh]). */
    suspend fun bootstrap() = mutex.withLock {
        val cached = store.loadCachedKnownRelayers()
        val config = store.loadConfiguration()
        _snapshots.value = _snapshots.value.copy(
            knownRelayers = cached,
            configuration = config,
        )
    }

    /** Fire-and-forget the GitHub-Releases fetch. Idempotent.
     *  Errors swallowed (app launch must not crash on a network
     *  blip); the [RelayerFetchStatus.Failed] snapshot is still
     *  published before the swallow so the picker UI can react. */
    suspend fun start() {
        val shouldFetch = mutex.withLock {
            if (startInvoked) false
            else { startInvoked = true; true }
        }
        if (shouldFetch) {
            runCatching { refreshInternal() }
        }
    }

    /** User-initiated refresh; failures propagate to the caller AND
     *  a [RelayerFetchStatus.Failed] snapshot is published. */
    suspend fun refresh() {
        refreshInternal()
    }

    /** Shared body of [start] + [refresh]. Publishes [RelayerFetchStatus.Fetching]
     *  → fetch → publishes [RelayerFetchStatus.Success] (via
     *  [applyKnownListAndAutoPopulateLocked]) OR
     *  [RelayerFetchStatus.Failed] then rethrows. */
    private suspend fun refreshInternal() {
        mutex.withLock {
            _snapshots.value = _snapshots.value.copy(fetchStatus = RelayerFetchStatus.Fetching)
        }
        val fresh = try {
            fetcher.fetch()
        } catch (e: Throwable) {
            mutex.withLock {
                _snapshots.value = _snapshots.value.copy(
                    fetchStatus = RelayerFetchStatus.Failed(errorMessageResolver(e)),
                )
            }
            throw e
        }
        mutex.withLock {
            store.saveCachedKnownRelayers(fresh)
            applyKnownListAndAutoPopulateLocked(fresh)
            _snapshots.value = _snapshots.value.copy(fetchStatus = RelayerFetchStatus.Success)
        }
    }

    /** Common path called by [refreshInternal] under the mutex:
     *  stash the fetched list, and if the user hasn't yet
     *  interacted with the picker, fan it into the configured
     *  endpoints (RANDOM strategy, no primary). Once
     *  [RelayerConfiguration.hasUserInteracted] flips, future
     *  fetches only update the cached known list — never the
     *  configuration. Preserves the current [RelayerFetchStatus]
     *  via `.copy()`; the caller sets it explicitly afterwards. */
    private suspend fun applyKnownListAndAutoPopulateLocked(fresh: List<RelayerEndpoint>) {
        val current = _snapshots.value.configuration
        val updatedConfig = if (!current.hasUserInteracted && fresh.isNotEmpty()) {
            RelayerConfiguration(
                endpoints = fresh,
                primaryUrl = null,
                strategy = RelayerStrategy.RANDOM,
                // Sticky — auto-populate runs once per install. From
                // here on, the user owns the list; subsequent
                // refreshes only refresh the cached published list.
                hasUserInteracted = true,
            )
        } else {
            current
        }
        if (updatedConfig != current) {
            store.saveConfiguration(updatedConfig)
        }
        _snapshots.value = _snapshots.value.copy(
            knownRelayers = fresh,
            configuration = updatedConfig,
        )
    }

    // ─── list-shaped mutators ─────────────────────────────────────

    /** Add (or replace metadata for) an endpoint, keyed by URL. If
     *  the URL is already configured, the metadata (name, networks)
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

    /** Common path for every list-shaped mutator. Promotes
     *  [RelayerConfiguration.hasUserInteracted] to `true` so a
     *  subsequent [refresh] doesn't blow away the user's choices
     *  via auto-populate. */
    private suspend fun applyConfigurationLocked(updated: RelayerConfiguration) {
        val withFlag = if (updated.hasUserInteracted) updated
                       else updated.copy(hasUserInteracted = true)
        store.saveConfiguration(withFlag)
        _snapshots.value = _snapshots.value.copy(configuration = withFlag)
    }
}
