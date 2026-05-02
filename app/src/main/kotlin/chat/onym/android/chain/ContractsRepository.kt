package chat.onym.android.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Snapshot the [ContractsRepository] publishes. Two parallel pieces
 * of state plus a derived resolver:
 *
 *  - [manifest] — last successful fetch (or null until one lands).
 *  - [selections] — user's explicit picks per `(network, type)`
 *    slot. Empty map means "no explicit selections — default-to-
 *    latest applies everywhere".
 *
 * [binding] is the load-bearing read path: chat-creation code calls
 * `state.binding(forKey)` to resolve the triple a brand-new chat
 * will carry forever. See the rules in the function's KDoc.
 */
data class ContractsState(
    val manifest: ContractsManifest?,
    val selections: Map<AnchorSelectionKey, String>,
) {
    /**
     * Resolve the [AnchorBinding] for [forKey] — the triple a
     * brand-new chat with this `(network, type)` slot would carry.
     *
     * Three rules, in order:
     *
     * 1. **Explicit selection wins** — if [selections] has an
     *    entry for [forKey] AND the manifest has a release with
     *    that tag AND that release has a contract for this
     *    `(network, type)` → return it.
     * 2. **Default to latest** — otherwise, find the latest
     *    release (highest `publishedAt`) that has a contract for
     *    this slot → return it.
     * 3. **No contract** — otherwise, return `null`. UI surfaces
     *    this as a disabled row ("No contracts yet" — typically
     *    the case for Mainnet on the day this PR lands).
     */
    fun binding(forKey: AnchorSelectionKey): AnchorBinding? {
        val mf = manifest ?: return null
        val explicitTag = selections[forKey]
        if (explicitTag != null) {
            val release = mf.releases.firstOrNull { it.release == explicitTag }
            val contract = release?.contracts?.firstOrNull {
                it.network == forKey.network && it.type == forKey.type
            }
            if (release != null && contract != null) {
                return AnchorBinding(
                    network = forKey.network,
                    governanceType = forKey.type,
                    contractId = contract.id,
                    release = release.release,
                )
            }
            // Fall through — explicit pick was stale; the user gets
            // default-to-latest behaviour until they re-pick.
        }
        // Releases are pre-sorted newest-first by ContractsManifest.fromRaw.
        for (release in mf.releases) {
            val contract = release.contracts.firstOrNull {
                it.network == forKey.network && it.type == forKey.type
            }
            if (contract != null) {
                return AnchorBinding(
                    network = forKey.network,
                    governanceType = forKey.type,
                    contractId = contract.id,
                    release = release.release,
                )
            }
        }
        return null
    }
}

/**
 * Owns the contracts-manifest cache + the user's anchor selections.
 * Same shape as [chat.onym.android.identity.IdentityRepository] —
 * Mutex + StateFlow.
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [store] + [fetcher]. No transport, no OnymSDK,
 *    no Compose, no `Context`.
 *  - Exposes a [StateFlow] of [ContractsState] for observation.
 *    [bootstrap] / [start] / [refresh] / [setSelection] /
 *    [clearSelection] are the suspend mutators.
 */
class ContractsRepository(
    private val store: AnchorSelectionStore,
    private val fetcher: ContractsManifestFetcher,
) {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow(
        ContractsState(manifest = null, selections = emptyMap())
    )

    val snapshots: StateFlow<ContractsState> = _snapshots.asStateFlow()

    private var startInvoked = false

    /** Hydrate from disk — cached manifest + selections. Idempotent. */
    suspend fun bootstrap() = mutex.withLock {
        val cached = store.loadCachedManifest()
        val sel = store.loadSelections()
        _snapshots.value = ContractsState(manifest = cached, selections = sel)
    }

    /** Fire-and-forget the GitHub-Releases fetch. Idempotent — a
     *  second [start] returns immediately. Failures swallowed. */
    suspend fun start() = mutex.withLock {
        if (startInvoked) return@withLock
        startInvoked = true
        runCatching { fetcher.fetch() }.onSuccess { result ->
            store.saveCachedManifest(result.rawJson)
            _snapshots.value = _snapshots.value.copy(manifest = result.manifest)
        }
    }

    /** User-initiated refresh. Failures propagate so a pull-to-
     *  refresh UI can show the error. */
    suspend fun refresh() {
        val result = fetcher.fetch()
        mutex.withLock {
            store.saveCachedManifest(result.rawJson)
            _snapshots.value = _snapshots.value.copy(manifest = result.manifest)
        }
    }

    /** Pick a release tag for a slot. Persists + pushes. */
    suspend fun setSelection(key: AnchorSelectionKey, releaseTag: String) = mutex.withLock {
        val updated = _snapshots.value.selections + (key to releaseTag)
        store.saveSelections(updated)
        _snapshots.value = _snapshots.value.copy(selections = updated)
    }

    /** "Reset to default" for a slot — clear the explicit pick so
     *  default-to-latest takes over. No-op if no pick existed. */
    suspend fun clearSelection(key: AnchorSelectionKey) = mutex.withLock {
        val current = _snapshots.value.selections
        if (key !in current) return@withLock
        val updated = current - key
        store.saveSelections(updated)
        _snapshots.value = _snapshots.value.copy(selections = updated)
    }
}
