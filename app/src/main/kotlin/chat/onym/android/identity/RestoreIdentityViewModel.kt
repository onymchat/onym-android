package chat.onym.android.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Screen-local ViewModel for Settings → Identities → Restore. Owns
 * the recovery-phrase + alias input, derives a [Validation] off the
 * phrase via [Bip39.entropyFromMnemonic], and on [submit] calls
 * [IdentityRepository.add] with `restoreMnemonic` set so the restored
 * identity lands alongside any existing ones (non-destructive) and
 * becomes the active selection.
 *
 * The input-state lives here (not in [IdentitiesViewModel]) so popping
 * the screen drops the typed phrase from memory; the shared
 * IdentitiesViewModel doesn't have to track per-screen ephemera.
 */
class RestoreIdentityViewModel(
    private val identity: IdentityRepository,
) : ViewModel() {

    /** Live validation tag. The screen disables Restore on anything but
     *  [Valid] and shows an inline hint on [Invalid]. [Empty] is the
     *  pristine state — no hint, button disabled. */
    enum class Validation { Empty, Invalid, Valid }

    /** Async submit lifecycle. [Done] is a one-shot terminal state the
     *  screen consumes to pop back; the caller acks via [acknowledgeDone]. */
    sealed class State {
        object Idle : State()
        object Restoring : State()
        data class Error(val cause: String) : State()
        object Done : State()
    }

    private val _phrase = MutableStateFlow("")
    val phrase: StateFlow<String> = _phrase.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val validation: StateFlow<Validation> = _phrase
        .map { raw ->
            val trimmed = raw.trim()
            when {
                trimmed.isEmpty() -> Validation.Empty
                Bip39.isValidMnemonic(trimmed) -> Validation.Valid
                else -> Validation.Invalid
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Validation.Empty)

    fun setPhrase(value: String) { _phrase.value = value }
    fun setName(value: String) { _name.value = value }

    fun submit() {
        if (_state.value is State.Restoring) return
        val trimmed = _phrase.value.trim()
        if (!Bip39.isValidMnemonic(trimmed)) return
        _state.value = State.Restoring
        viewModelScope.launch {
            try {
                identity.add(name = _name.value.trim(), restoreMnemonic = trimmed)
                _state.value = State.Done
            } catch (e: Throwable) {
                _state.value = State.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearError() {
        if (_state.value is State.Error) _state.value = State.Idle
    }

    /** Caller invokes after handling [State.Done] (typically a navigate-pop)
     *  so the flow doesn't re-fire on recomposition. */
    fun acknowledgeDone() {
        if (_state.value is State.Done) _state.value = State.Idle
    }
}
