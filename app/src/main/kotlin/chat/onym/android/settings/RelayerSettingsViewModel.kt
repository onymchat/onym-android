package chat.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.onym.android.chain.RelayerConfiguration
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerFetchStatus
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.RelayerStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

/**
 * ViewModel for the multi-endpoint Relayer settings screen.
 * Renamed from `RelayerPickerViewModel` (PR #17) when the picker
 * grew from "pick one" to "configure many + strategy".
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [repository] + the [validate] companion.
 *  - No `Context`, no DataStore, no OkHttp.
 *  - Intents: [addKnown], [tappedAddCustom], [removeEndpoint],
 *    [setPrimary], [setStrategy], [customDraftChanged].
 *
 * Mirrors `RelayerSettingsFlow` from onym-ios PR #20.
 */
class RelayerSettingsViewModel(
    private val repository: RelayerRepository,
) : ViewModel() {

    /** Snapshot the screen renders. */
    data class State(
        val configuration: RelayerConfiguration,
        /** Full published list as last seen on snapshot — exposed so
         *  the screen can show counts / empty-state copy independently
         *  of the unconfigured-subset gate. */
        val knownList: List<RelayerEndpoint>,
        /** Subset of [knownList] filtered to remove URLs already in
         *  [configuration] endpoints — the "Add from Published List"
         *  section shouldn't ghost an add as a no-op. */
        val unconfiguredKnownList: List<RelayerEndpoint>,
        /** Drives the 4-way gate on the screen
         *  (Idle / Fetching / Success / Failed). */
        val fetchStatus: RelayerFetchStatus,
        val customDraft: String,
        val customDraftError: String?,
    )

    private val _state = MutableStateFlow(
        State(
            configuration = RelayerConfiguration(),
            knownList = emptyList(),
            unconfiguredKnownList = emptyList(),
            fetchStatus = RelayerFetchStatus.Idle,
            customDraft = "",
            customDraftError = null,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.snapshots.collect { snapshot ->
                val configuredUrls = snapshot.configuration.endpoints.map { it.url }.toSet()
                _state.value = _state.value.copy(
                    configuration = snapshot.configuration,
                    knownList = snapshot.knownRelayers,
                    unconfiguredKnownList = snapshot.knownRelayers
                        .filterNot { it.url in configuredUrls },
                    fetchStatus = snapshot.fetchStatus,
                )
            }
        }
    }

    // ─── intents ──────────────────────────────────────────────────

    fun addKnown(endpoint: RelayerEndpoint) {
        viewModelScope.launch { repository.addEndpoint(endpoint) }
    }

    fun customDraftChanged(text: String) {
        _state.value = _state.value.copy(
            customDraft = text,
            customDraftError = null,  // typing clears stale error
        )
    }

    /** Validate the draft + add as a custom endpoint. On success
     *  the draft clears so the field is ready for the next entry. */
    fun tappedAddCustom() {
        val draft = _state.value.customDraft
        when (val r = validate(draft)) {
            is ValidationResult.Valid -> {
                viewModelScope.launch {
                    repository.addEndpoint(RelayerEndpoint.custom(r.normalisedUrl))
                }
                _state.value = _state.value.copy(customDraft = "", customDraftError = null)
            }
            is ValidationResult.Invalid -> {
                _state.value = _state.value.copy(customDraftError = r.reason)
            }
        }
    }

    fun removeEndpoint(url: String) {
        viewModelScope.launch { repository.removeEndpoint(url) }
    }

    fun setPrimary(url: String) {
        viewModelScope.launch { repository.setPrimary(url) }
    }

    fun setStrategy(strategy: RelayerStrategy) {
        viewModelScope.launch { repository.setStrategy(strategy) }
    }

    /** "Try Again" tap on the [RelayerFetchStatus.Failed] gate. The
     *  repository republishes Fetching → Success/Failed, which the
     *  screen reflects via the snapshot collector. We swallow the
     *  rethrow here — the user-visible error already lives on the
     *  next snapshot. */
    fun tappedRetryFetch() {
        viewModelScope.launch { runCatching { repository.refresh() } }
    }

    sealed class ValidationResult {
        data class Valid(val normalisedUrl: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    companion object {
        /** Same rules as PR #17: trim → require http/https → require
         *  non-empty host. */
        internal fun validate(raw: String): ValidationResult {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ValidationResult.Invalid("URL is empty")
            val uri = try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return ValidationResult.Invalid("URL didn't parse")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return ValidationResult.Invalid("scheme must be http or https")
            }
            val host = uri.host
            if (host.isNullOrBlank()) {
                return ValidationResult.Invalid("URL has no host")
            }
            return ValidationResult.Valid(trimmed)
        }
    }
}
