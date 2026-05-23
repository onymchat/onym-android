package app.onym.android.identity

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
 *  - [add] — append an identity alongside the existing ones. With a
 *    blank mnemonic it mints a fresh BIP39 identity; with a 12/24-word
 *    phrase it restores an existing one. The outcome lands on
 *    [addResult] so the Add-Identity dialog can dismiss on success or
 *    inline an "invalid phrase" message (mirrors the iOS
 *    `IdentitiesFlow.submitAdd` + `addError` pattern).
 *  - [remove] — gated by a typed-name confirm. The repository's
 *    removal listener (registered by `GroupRepository`) cascades a
 *    delete-by-owner over the identity's chats.
 *  - [rename] — change an identity's display alias. Trimmed-blank
 *    input is a silent no-op in the repository (matches the iOS
 *    inline-edit "blur with empty input keeps old name" pattern).
 *
 * Errors raised by the repository surface on [error]; UI shows them
 * as a Snackbar / inline banner (resolving the structured value to a
 * localized string) and clears via [clearError].
 */
class IdentitiesViewModel(
    private val identity: IdentityRepository,
) : ViewModel() {

    /** Each row the screen renders: identity summary + whether it's
     *  the currently-selected one. */
    data class Row(val summary: IdentitySummary, val isActive: Boolean)

    /** Structured error so the UI can render a localized message.
     *  [cause] carries the underlying exception's message (or class
     *  name when null) — substituted into a `%1$s` placeholder. */
    sealed class Error {
        abstract val cause: String

        data class Switch(override val cause: String) : Error()
        data class Remove(override val cause: String) : Error()
        data class Rename(override val cause: String) : Error()
    }

    /** Outcome of the most recent [add] attempt, or `null` while idle.
     *  The Add-Identity dialog observes this: [Success] dismisses it,
     *  [InvalidMnemonic] / [Failure] inline an error so the user can
     *  fix the phrase without losing their input. Modelling success
     *  explicitly (rather than iOS's "dismiss if addError == nil"
     *  optimism) keeps the dismiss off the racy path. */
    sealed interface AddResult {
        object Success : AddResult
        object InvalidMnemonic : AddResult
        data class Failure(val cause: String) : AddResult
    }

    val items: StateFlow<List<Row>> = combine(
        identity.identities,
        identity.currentIdentityId,
    ) { list, activeId ->
        list.map { Row(summary = it, isActive = it.id == activeId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _error = MutableStateFlow<Error?>(null)
    val error: StateFlow<Error?> = _error.asStateFlow()

    private val _addResult = MutableStateFlow<AddResult?>(null)
    val addResult: StateFlow<AddResult?> = _addResult.asStateFlow()

    fun select(id: IdentityId) {
        viewModelScope.launch {
            try {
                identity.select(id)
            } catch (e: Throwable) {
                _error.value = Error.Switch(e.causeText())
            }
        }
    }

    /** Add an identity. A blank/null [restoreMnemonic] mints a fresh
     *  BIP39 identity; a 12/24-word phrase restores an existing one.
     *  The result lands on [addResult] — the dialog reacts; clear it
     *  with [clearAddResult]. */
    fun add(name: String = "", restoreMnemonic: String? = null) {
        viewModelScope.launch {
            try {
                identity.add(
                    name = name,
                    restoreMnemonic = restoreMnemonic?.trim()?.ifBlank { null },
                )
                _addResult.value = AddResult.Success
            } catch (e: IdentityError.InvalidMnemonic) {
                _addResult.value = AddResult.InvalidMnemonic
            } catch (e: Throwable) {
                _addResult.value = AddResult.Failure(e.causeText())
            }
        }
    }

    fun clearAddResult() {
        _addResult.value = null
    }

    fun remove(id: IdentityId) {
        viewModelScope.launch {
            try {
                identity.remove(id)
            } catch (e: Throwable) {
                _error.value = Error.Remove(e.causeText())
            }
        }
    }

    /** Rename [id] to [newName]. Trimmed-blank inputs are a silent
     *  no-op in the repository — UI doesn't need to pre-validate. */
    fun rename(id: IdentityId, newName: String) {
        viewModelScope.launch {
            try {
                identity.rename(id, newName)
            } catch (e: Throwable) {
                _error.value = Error.Rename(e.causeText())
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun Throwable.causeText(): String = message ?: javaClass.simpleName
}
