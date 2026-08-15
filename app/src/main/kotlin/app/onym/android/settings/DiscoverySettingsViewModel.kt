package app.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.discovery.DiscoveryRepository
import app.onym.android.discovery.DiscoveryState
import app.onym.android.discovery.PendingDiscoverySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI

/**
 * ViewModel for Settings → Discovery: the configured provider list
 * (status, fingerprint, per-source entry counts, permanent remove)
 * plus the add-provider sub-flow (URL → fetched preview → TOFU
 * fingerprint confirm), which lives here because its outcome mutates
 * the same source list this screen renders.
 *
 * Mirrors `DiscoverySettingsFlow.swift` from onym-ios
 * `discovery-settings-ui`.
 */
class DiscoverySettingsViewModel(
    private val repository: DiscoveryRepository,
) : ViewModel() {

    /**
     * Where the add-provider flow currently stands. The pending
     * source in [Confirming] is fully verified but **nothing is
     * pinned** until the user confirms the operator key fingerprint
     * ([DiscoveryRepository.addSource] contract).
     */
    sealed class AddPhase {
        data object Idle : AddPhase()
        data object Fetching : AddPhase()
        data class Confirming(val pending: PendingDiscoverySource) : AddPhase()
        data object Added : AddPhase()
    }

    data class State(
        val discovery: DiscoveryState = DiscoveryState.empty,
        val addDraft: String = "",
        val addPhase: AddPhase = AddPhase.Idle,
        val addError: String? = null,
    ) {
        /** Verified catalog entries a source currently contributes. */
        fun entryCount(providerId: String): Int =
            discovery.entries.count { it.attribution.providerId == providerId }
    }

    private val _addDraft = MutableStateFlow("")
    private val _addPhase = MutableStateFlow<AddPhase>(AddPhase.Idle)
    private val _addError = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        repository.snapshots,
        _addDraft,
        _addPhase,
        _addError,
    ) { discovery, draft, phase, error ->
        State(discovery = discovery, addDraft = draft, addPhase = phase, addError = error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = State(),
    )

    // ─── provider list intents ────────────────────────────────────

    /**
     * Confirmed removal. Permanent by design: for a shipped default
     * the providerId joins `removedDefaultProviderIds`, so no future
     * seeding restores it — only an explicit re-add does.
     */
    fun tappedRemove(providerId: String) {
        viewModelScope.launch { repository.removeSource(providerId) }
    }

    /** User-initiated refresh; the failure surfaces on the snapshot. */
    fun tappedRefresh() {
        viewModelScope.launch { runCatching { repository.refresh() } }
    }

    // ─── add-provider sub-flow ────────────────────────────────────

    fun addDraftChanged(text: String) {
        _addDraft.value = text
        _addError.value = null
    }

    /** "Fetch" in the add flow: validate the typed URL, fetch +
     *  verify the manifest, move to the TOFU confirmation step. */
    fun tappedFetchProvider() {
        val url = validate(_addDraft.value)
        if (url == null) {
            _addError.value = "Enter a valid https:// URL"
            return
        }
        _addError.value = null
        _addPhase.value = AddPhase.Fetching
        viewModelScope.launch {
            try {
                val pending = repository.addSource(url)
                _addPhase.value = AddPhase.Confirming(pending)
            } catch (e: Exception) {
                _addError.value = e.message ?: "Couldn't fetch the provider manifest."
                _addPhase.value = AddPhase.Idle
            }
        }
    }

    /** TOFU confirm: pin the previewed operator key, persist the
     *  source, and refresh so its catalogs land in the aggregate. */
    fun tappedConfirmAdd() {
        val phase = _addPhase.value as? AddPhase.Confirming ?: return
        viewModelScope.launch {
            repository.confirmAddSource(phase.pending)
            _addPhase.value = AddPhase.Added
        }
    }

    /** Back out of the TOFU confirmation without pinning anything. */
    fun tappedCancelPreview() {
        if (_addPhase.value is AddPhase.Confirming) _addPhase.value = AddPhase.Idle
    }

    /** Reset the add flow (screen dismissed). */
    fun resetAdd() {
        _addDraft.value = ""
        _addPhase.value = AddPhase.Idle
        _addError.value = null
    }

    companion object {
        /**
         * Discovery manifests are https-only (profile §7 URI rules) —
         * stricter than the relayer picker's http allowance on
         * purpose. Returns the trimmed URL or null.
         */
        fun validate(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val uri = URI(trimmed)
                if (uri.scheme?.lowercase() == "https" && !uri.host.isNullOrEmpty()) trimmed
                else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
