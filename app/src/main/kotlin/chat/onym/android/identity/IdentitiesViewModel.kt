package chat.onym.android.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for Settings → Identities.
 *
 * Reads the multi-identity surface off [IdentityRepository] and
 * exposes a flat [items] list the screen renders directly. Drives
 * the three intents:
 *
 *  - [select] — flip the active identity.
 *  - [add] — append a fresh BIP39-backed identity (V1 bootstrap
 *    only; restore-from-mnemonic UI lives in the recovery flow and
 *    isn't reachable from here).
 *  - [remove] — gated by a typed-name confirm. The repository's
 *    removal listener (registered by `GroupRepository`) cascades a
 *    delete-by-owner over the identity's chats.
 *
 * Errors raised by the repository surface on [errorMessage]; UI
 * shows them as a Snackbar / inline banner and clears via
 * [clearError].
 */
class IdentitiesViewModel(
    private val identity: IdentityRepository,
) : ViewModel() {

    /** Each row the screen renders: identity summary + whether it's
     *  the currently-selected one. */
    data class Row(val summary: IdentitySummary, val isActive: Boolean)

    val items: StateFlow<List<Row>> = combine(
        identity.identities,
        identity.currentIdentityId,
    ) { list, activeId ->
        list.map { Row(summary = it, isActive = it.id == activeId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun select(id: IdentityId) {
        viewModelScope.launch {
            try {
                identity.select(id)
            } catch (e: Throwable) {
                _errorMessage.value = "Couldn't switch identity: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun add(name: String = "") {
        viewModelScope.launch {
            try {
                identity.add(name = name)
            } catch (e: Throwable) {
                _errorMessage.value = "Couldn't add identity: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun remove(id: IdentityId) {
        viewModelScope.launch {
            try {
                identity.remove(id)
            } catch (e: Throwable) {
                _errorMessage.value = "Couldn't remove identity: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
