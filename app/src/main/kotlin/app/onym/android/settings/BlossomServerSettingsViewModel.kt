package app.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.transport.blossom.BlossomServerEndpoint
import app.onym.android.transport.blossom.BlossomServersConfiguration
import app.onym.android.transport.blossom.BlossomServersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI

/**
 * View-state for the Settings → Transport → Blossom Relays screen.
 *
 * Mirrors [NostrRelaySettingsViewModel] and onym-ios
 * `BlossomRelaySettingsFlow.swift`. Blossom servers speak HTTP(S), so
 * validation + placeholder copy differ from the wss:// Nostr screen.
 */
data class BlossomServerSettingsState(
    val snapshot: BlossomServersConfiguration = BlossomServersConfiguration.empty,
    val customDraft: String = "",
    val customDraftError: String? = null,
)

class BlossomServerSettingsViewModel(
    private val repository: BlossomServersRepository,
) : ViewModel() {

    private val _draft = MutableStateFlow("")
    private val _draftError = MutableStateFlow<String?>(null)

    val state: StateFlow<BlossomServerSettingsState> = combine(
        repository.snapshots,
        _draft,
        _draftError,
    ) { snapshot, draft, error ->
        BlossomServerSettingsState(snapshot = snapshot, customDraft = draft, customDraftError = error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = BlossomServerSettingsState(),
    )

    fun customDraftChanged(text: String) {
        _draft.value = text
        _draftError.value = null
    }

    fun tappedAddCustom() {
        val raw = _draft.value
        val normalized = validate(raw)
        if (normalized == null) {
            _draftError.value =
                "Use https:// or http:// with a hostname, e.g. https://blossom.example.com"
            return
        }
        viewModelScope.launch {
            val added = repository.addEndpoint(BlossomServerEndpoint.custom(normalized))
            if (added) {
                _draft.value = ""
                _draftError.value = null
            } else {
                _draftError.value = "That URL is already configured."
            }
        }
    }

    fun tappedRemove(url: String) {
        viewModelScope.launch { repository.removeEndpoint(url) }
    }

    fun tappedResetToDefault() {
        viewModelScope.launch { repository.resetToDefault() }
    }

    companion object {
        /** Returns the trimmed URL when it's a valid https/http URL with
         *  a non-empty host; null otherwise. */
        fun validate(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host
                if ((scheme == "https" || scheme == "http") && !host.isNullOrEmpty()) trimmed
                else null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
