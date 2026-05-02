package chat.onym.android.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the in-memory view of the relayer URL configuration plus
 * the corresponding writes to the persistence + network seams.
 * Same shape as [chat.onym.android.identity.IdentityRepository] —
 * Mutex + StateFlow.
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [store] + [fetcher]. No transport, no OnymSDK,
 *    no Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [setSelection] /
 *    [start] / [refresh] / [bootstrap] are the suspend mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free
 *    off the [StateFlow].
 *
 * Mirrors `RelayerRepository` from onym-ios PR #18.
 */
class RelayerRepository(
    private val store: RelayerSelectionStore,
    private val fetcher: KnownRelayersFetcher,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(RelayerState(emptyList(), null))

    /** Hot stream of [RelayerState] snapshots. New collectors get
     *  the current value immediately, then one new value per
     *  successful mutation. */
    val snapshots: StateFlow<RelayerState> = _snapshots.asStateFlow()

    /** `true` after the first [start] call so a re-launch (e.g.,
     *  Activity recreate) doesn't double-fetch. Wraps inside the
     *  mutex; never read off it. */
    private var startInvoked = false

    /**
     * Hydrate from disk — the cached known list + the user's last
     * selection. Idempotent. Safe to call from any startup path
     * (e.g., on `OnymApplication.onCreate` before [start] kicks
     * off a network fetch).
     */
    suspend fun bootstrap() = mutex.withLock {
        val cached = store.loadCachedKnownRelayers()
        val sel = store.loadSelection()
        _snapshots.value = RelayerState(knownRelayers = cached, selection = sel)
    }

    /**
     * Fire-and-forget the GitHub-Releases fetch. Idempotent (a
     * second [start] call returns immediately). Failures are
     * swallowed — app launch must not crash on a network blip;
     * the picker shows the cached list until the next [refresh].
     */
    suspend fun start() = mutex.withLock {
        if (startInvoked) return@withLock
        startInvoked = true
        runCatching { fetcher.fetch() }.onSuccess { fresh ->
            store.saveCachedKnownRelayers(fresh)
            _snapshots.value = _snapshots.value.copy(knownRelayers = fresh)
        }
    }

    /**
     * User-initiated refresh. Same as [start] but ungated by
     * [startInvoked] — explicit user action gets a fresh fetch
     * even after the boot fetch already ran. Failures are
     * propagated to the caller (so a pull-to-refresh UI can show
     * the error).
     */
    suspend fun refresh() {
        val fresh = fetcher.fetch()  // throws on failure; caller handles
        mutex.withLock {
            store.saveCachedKnownRelayers(fresh)
            _snapshots.value = _snapshots.value.copy(knownRelayers = fresh)
        }
    }

    /** Persist the user's pick + push a fresh snapshot. `null`
     *  clears the selection (chain code refuses to publish until
     *  the user picks again). */
    suspend fun setSelection(selection: RelayerSelection?) = mutex.withLock {
        store.saveSelection(selection)
        _snapshots.value = _snapshots.value.copy(selection = selection)
    }
}
