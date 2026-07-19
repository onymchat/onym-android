package app.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.chain.AnchorBinding
import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.AppNetwork
import app.onym.android.chain.ContractEntry
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.ContractRelease
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.NetworkPreferenceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel for the Anchors drill-down. Holds **flow** state
 * (snapshot mirror); domain state lives on [ContractsRepository].
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [repository].
 *  - No `Context`, no DataStore, no OkHttp.
 *  - Stateless w.r.t. navigation — Compose's NavController owns
 *    the back stack; this ViewModel just answers per-screen
 *    queries against the snapshot.
 */
class AnchorsPickerViewModel(
    private val repository: ContractsRepository,
    private val networkPreference: NetworkPreferenceProvider,
) : ViewModel() {

    /** Top-level state mirror. */
    data class State(
        val hasManifest: Boolean,
        val networkAvailability: Map<ContractNetwork, Boolean>,
        /** The active network for new chats (was the global "Use Mainnet"
         *  toggle; now selected here). */
        val activeNetwork: AppNetwork,
    )

    private val _state = MutableStateFlow(
        State(
            hasManifest = false,
            networkAvailability = ContractNetwork.entries.associateWith { false },
            activeNetwork = networkPreference.current(),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.snapshots, networkPreference.flow) { snapshot, active ->
                val mf = snapshot.manifest
                State(
                    hasManifest = mf != null,
                    networkAvailability = ContractNetwork.entries.associateWith { network ->
                        mf?.releases?.any { release ->
                            release.contracts.any { it.network == network }
                        } == true
                    },
                    activeNetwork = active,
                )
            }.collect { _state.value = it }
        }
    }

    /** Set the active network for new chats. */
    fun setActiveNetwork(network: AppNetwork) {
        viewModelScope.launch { networkPreference.set(network) }
    }

    // ─── Network screen ───────────────────────────────────────────

    /** Per-row data for the Network screen — one row per
     *  [GovernanceType]. */
    data class NetworkRow(
        val type: GovernanceType,
        val resolvedRelease: String?,    // null if no contract for this slot
        val isExplicit: Boolean,         // true if user picked; false if defaulted
    )

    /** Snapshot the Network screen renders. Recomputed on every
     *  call against the current [ContractsRepository] snapshot;
     *  composables can call this from their `remember` block. */
    fun networkRows(network: ContractNetwork): List<NetworkRow> {
        val snapshot = repository.snapshots.value
        return GovernanceType.entries.map { type ->
            val key = AnchorSelectionKey(network = network, type = type)
            val binding = snapshot.binding(forKey = key)
            NetworkRow(
                type = type,
                resolvedRelease = binding?.release,
                isExplicit = key in snapshot.selections,
            )
        }
    }

    // ─── Version screen ───────────────────────────────────────────

    /** Per-row data for the Version screen — one row per release
     *  that has a contract for this `(network, type)` slot. */
    data class VersionRow(
        val release: ContractRelease,
        val entry: ContractEntry,
        val isCurrentlySelected: Boolean,    // matches resolved binding
        val isExplicitPick: Boolean,         // matches explicit selection (not defaulted)
    )

    fun versionRows(network: ContractNetwork, type: GovernanceType): List<VersionRow> {
        val snapshot = repository.snapshots.value
        val mf = snapshot.manifest ?: return emptyList()
        val key = AnchorSelectionKey(network = network, type = type)
        val resolved = snapshot.binding(forKey = key)
        val explicitTag = snapshot.selections[key]
        return mf.releases.mapNotNull { release ->
            val entry = release.contracts.firstOrNull {
                it.network == network && it.type == type
            } ?: return@mapNotNull null
            VersionRow(
                release = release,
                entry = entry,
                isCurrentlySelected = release.release == resolved?.release,
                isExplicitPick = release.release == explicitTag,
            )
        }
    }

    /** Read-only resolution helper — the binding a brand-new chat
     *  with this slot would carry today. UI uses it for the
     *  "currently resolved" subtitle. */
    fun currentBinding(network: ContractNetwork, type: GovernanceType): AnchorBinding? =
        repository.snapshots.value.binding(
            forKey = AnchorSelectionKey(network = network, type = type)
        )

    // ─── Intents ──────────────────────────────────────────────────

    fun pickVersion(network: ContractNetwork, type: GovernanceType, releaseTag: String) {
        viewModelScope.launch {
            repository.setSelection(
                key = AnchorSelectionKey(network = network, type = type),
                releaseTag = releaseTag,
            )
        }
    }

    fun resetToDefault(network: ContractNetwork, type: GovernanceType) {
        viewModelScope.launch {
            repository.clearSelection(
                key = AnchorSelectionKey(network = network, type = type),
            )
        }
    }
}
