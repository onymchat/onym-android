package app.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.transport.nostr.NostrRelayEndpoint
import app.onym.android.transport.nostr.NostrRelaysConfiguration
import app.onym.android.transport.nostr.NostrRelaysRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI

/**
 * View-state for the Settings → Transport → Nostr Relays screen.
 *
 * Mirrors `NostrRelaySettingsFlow.swift` from onym-ios PR #87.
 */
data class NostrRelaySettingsState(
    val snapshot: NostrRelaysConfiguration = NostrRelaysConfiguration.empty,
    val customDraft: String = "",
    val customDraftError: String? = null,
)

class NostrRelaySettingsViewModel(
    private val repository: NostrRelaysRepository,
) : ViewModel() {

    private val _draft = MutableStateFlow("")
    private val _draftError = MutableStateFlow<String?>(null)

    val state: StateFlow<NostrRelaySettingsState> = combine(
        repository.snapshots,
        _draft,
        _draftError,
    ) { snapshot, draft, error ->
        NostrRelaySettingsState(snapshot = snapshot, customDraft = draft, customDraftError = error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = NostrRelaySettingsState(),
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
                "Use wss:// or ws:// with a hostname, e.g. wss://relay.example.com"
            return
        }
        viewModelScope.launch {
            val added = repository.addEndpoint(NostrRelayEndpoint.custom(normalized))
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
        /** Returns the trimmed URL when it's a valid wss/ws URL with a
         *  non-empty host; null otherwise. */
        fun validate(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host
                if ((scheme == "wss" || scheme == "ws") && !host.isNullOrEmpty()) trimmed
                else null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
