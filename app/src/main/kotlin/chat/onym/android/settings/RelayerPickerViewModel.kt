package chat.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.RelayerSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

/**
 * ViewModel for the Relayer picker screen. Holds the **flow** state
 * (current snapshot mirror + draft + validation error); domain
 * state lives on [RelayerRepository].
 *
 * Touch-surface compliance:
 *
 *  - Holds **only** [repository] + the [validate] companion.
 *  - No `Context`, no DataStore, no OkHttp.
 *  - Intents: [pickKnown], [customDraftChanged], [saveCustom],
 *    [clearSelection]. Each dispatches to [repository] on
 *    [viewModelScope].
 *
 * Mirrors `RelayerPickerFlow` from onym-ios PR #18.
 */
class RelayerPickerViewModel(
    private val repository: RelayerRepository,
) : ViewModel() {

    /** Snapshot the screen renders. */
    data class State(
        val knownRelayers: List<RelayerEndpoint>,
        val currentSelection: RelayerSelection?,
        /** User-typed draft for the Custom row; never round-trips
         *  through the repository until [saveCustom]. */
        val customDraft: String,
        /** Non-null when the draft fails [validate]; `null` when
         *  the draft is empty (no error yet) or valid. */
        val customDraftError: String?,
    )

    private val _state = MutableStateFlow(
        State(
            knownRelayers = emptyList(),
            currentSelection = null,
            customDraft = "",
            customDraftError = null,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.snapshots.collect { snapshot ->
                val current = _state.value
                // Prefill the draft from the persisted custom URL on
                // first hydration, but never overwrite an in-flight
                // user edit (compare draft to the previous selection
                // string, not the new one).
                val newDraft = if (
                    current.customDraft.isEmpty() ||
                    current.customDraft == (current.currentSelection as? RelayerSelection.Custom)?.url
                ) {
                    (snapshot.selection as? RelayerSelection.Custom)?.url ?: current.customDraft
                } else {
                    current.customDraft
                }
                _state.value = current.copy(
                    knownRelayers = snapshot.knownRelayers,
                    currentSelection = snapshot.selection,
                    customDraft = newDraft,
                )
            }
        }
    }

    fun pickKnown(endpoint: RelayerEndpoint) {
        viewModelScope.launch {
            repository.setSelection(RelayerSelection.Known(endpoint))
        }
    }

    fun customDraftChanged(text: String) {
        _state.value = _state.value.copy(
            customDraft = text,
            // Clear stale error as the user types; we re-validate
            // on Save.
            customDraftError = null,
        )
    }

    /** Validates the current draft and, on success, persists it as
     *  a [RelayerSelection.Custom]. Sets [State.customDraftError]
     *  on failure; never touches the repository. */
    fun saveCustom() {
        val draft = _state.value.customDraft
        when (val result = validate(draft)) {
            is ValidationResult.Valid -> {
                viewModelScope.launch {
                    repository.setSelection(RelayerSelection.Custom(result.normalisedUrl))
                }
            }
            is ValidationResult.Invalid -> {
                _state.value = _state.value.copy(customDraftError = result.reason)
            }
        }
    }

    fun clearSelection() {
        viewModelScope.launch {
            repository.setSelection(null)
        }
    }

    sealed class ValidationResult {
        data class Valid(val normalisedUrl: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    companion object {
        /**
         * URL validation rules — parity with iOS PR #18.
         *
         *  - Trim whitespace.
         *  - Reject empty.
         *  - Must parse as a URI.
         *  - Scheme must be `http` or `https` (so `http://localhost:8080`
         *    works for dev).
         *  - Host must be non-empty.
         */
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
