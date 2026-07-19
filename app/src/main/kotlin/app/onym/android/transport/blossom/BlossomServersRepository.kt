package app.onym.android.transport.blossom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the in-memory view of [BlossomServersConfiguration] + writes to
 * the persistence seam. The Blossom client reads the server list once
 * at boot via [currentEndpoints] (uploads/downloads target the first
 * configured server); live re-config is out of scope for V1.
 *
 * First-launch seed: if storage starts empty AND
 * [BlossomServersConfiguration.hasUserInteracted] is false, the
 * repository writes [BlossomServersConfiguration.seed] back to disk so
 * subsequent launches load the seeded config without going through the
 * seed branch again. The bit flips to `true` on every mutation EXCEPT
 * [resetToDefault].
 *
 * Mirrors `NostrRelaysRepository` and onym-ios
 * `BlossomServersRepository.swift`.
 */
class BlossomServersRepository(
    private val store: BlossomServersSelectionStore,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(BlossomServersConfiguration.empty)
    val snapshots: StateFlow<BlossomServersConfiguration> = _snapshots.asStateFlow()

    /** Hydrate from disk + apply the first-launch seed when relevant.
     *  Idempotent — safe to call from app start. */
    suspend fun bootstrap() = mutex.withLock {
        val loaded = store.load()
        val effective = if (loaded.endpoints.isEmpty() && !loaded.hasUserInteracted) {
            store.save(BlossomServersConfiguration.seed)
            BlossomServersConfiguration.seed
        } else {
            loaded
        }
        _snapshots.value = effective
    }

    /** Read snapshot — non-suspend convenience for boot wiring. */
    fun currentEndpoints(): List<BlossomServerEndpoint> = _snapshots.value.endpoints

    /** Append [endpoint] when its URL isn't already configured.
     *  Returns `true` on append, `false` on duplicate-no-op. Flips
     *  [BlossomServersConfiguration.hasUserInteracted] to `true`. */
    suspend fun addEndpoint(endpoint: BlossomServerEndpoint): Boolean = mutex.withLock {
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
     *  [BlossomServersConfiguration.hasUserInteracted] to `true` even
     *  when the URL isn't configured (the user pressed Remove). */
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
        store.save(BlossomServersConfiguration.seed)
        _snapshots.value = BlossomServersConfiguration.seed
    }

    /** Wipe every endpoint (without going through the seed branch).
     *  interactionFlag stays `true` so we don't re-seed at next
     *  launch. */
    suspend fun clearAll() = mutex.withLock {
        val updated = BlossomServersConfiguration(
            endpoints = emptyList(),
            hasUserInteracted = true,
        )
        store.save(updated)
        _snapshots.value = updated
    }
}
