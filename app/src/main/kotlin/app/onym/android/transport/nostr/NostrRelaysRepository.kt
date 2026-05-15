package app.onym.android.transport.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the in-memory view of [NostrRelaysConfiguration] + writes to
 * the persistence seam. The inbox transport reads relay endpoints
 * once at boot via [currentEndpoints]; live re-connect on settings
 * change is out of scope for V1.
 *
 * First-launch seed: if storage starts empty AND
 * [NostrRelaysConfiguration.hasUserInteracted] is false, the
 * repository writes [NostrRelaysConfiguration.seed] back to disk
 * so subsequent launches load the seeded config without going
 * through the seed branch again. The bit flips to `true` on every
 * mutation EXCEPT [resetToDefault] (which restores the seed and
 * re-enables seeding for any future "clear + relaunch" flow).
 *
 * Mirrors `NostrRelaysRepository.swift` from onym-ios PR #87.
 */
class NostrRelaysRepository(
    private val store: NostrRelaysSelectionStore,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(NostrRelaysConfiguration.empty)
    val snapshots: StateFlow<NostrRelaysConfiguration> = _snapshots.asStateFlow()

    /** Hydrate from disk + apply the first-launch seed when relevant.
     *  Idempotent — safe to call from `onCreate` / app start. */
    suspend fun bootstrap() = mutex.withLock {
        val loaded = store.load()
        val effective = if (loaded.endpoints.isEmpty() && !loaded.hasUserInteracted) {
            store.save(NostrRelaysConfiguration.seed)
            NostrRelaysConfiguration.seed
        } else {
            loaded
        }
        _snapshots.value = effective
    }

    /** Read snapshot — non-suspend convenience for boot wiring. */
    fun currentEndpoints(): List<NostrRelayEndpoint> = _snapshots.value.endpoints

    /** Append [endpoint] when its URL isn't already configured.
     *  Returns `true` on append, `false` on duplicate-no-op. Flips
     *  [NostrRelaysConfiguration.hasUserInteracted] to `true`. */
    suspend fun addEndpoint(endpoint: NostrRelayEndpoint): Boolean = mutex.withLock {
        val current = _snapshots.value
        if (current.endpoints.any { it.url == endpoint.url }) return@withLock false
        val updated = current.copy(
            endpoints = current.endpoints + endpoint,
            hasUserInteracted = true,
        )
        store.save(updated)
        _snapshots.value = updated
        true
    }

    /** Remove the endpoint matching [url]. Flips
     *  [NostrRelaysConfiguration.hasUserInteracted] to `true` even
     *  when the URL isn't configured (matches iOS — the user pressed
     *  Remove, that's interaction). */
    suspend fun removeEndpoint(url: String) = mutex.withLock {
        val current = _snapshots.value
        val updated = current.copy(
            endpoints = current.endpoints.filter { it.url != url },
            hasUserInteracted = true,
        )
        store.save(updated)
        _snapshots.value = updated
    }

    /** Restore the seeded default. Resets [hasUserInteracted] to
     *  `false` so a subsequent "clear all" + relaunch re-enables the
     *  first-launch seed branch. */
    suspend fun resetToDefault() = mutex.withLock {
        store.save(NostrRelaysConfiguration.seed)
        _snapshots.value = NostrRelaysConfiguration.seed
    }

    /** Wipe every endpoint (without going through the seed branch).
     *  Useful when the user wants an explicitly-empty configuration
     *  — interactionFlag stays `true` so we don't re-seed at next
     *  launch. */
    suspend fun clearAll() = mutex.withLock {
        val updated = NostrRelaysConfiguration(
            endpoints = emptyList(),
            hasUserInteracted = true,
        )
        store.save(updated)
        _snapshots.value = updated
    }
}
