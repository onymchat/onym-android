package chat.onym.android.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.onym.android.group.ChatGroup
import chat.onym.android.group.GroupRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Read-only ViewModel for the Chats tab. Drains
 * [GroupRepository.snapshots] into a [StateFlow] the screen collects
 * via `collectAsStateWithLifecycle()`. No mutating intents — the
 * row UI is read-only and the only action ("open Create Group") is
 * a navigation callback the screen passes up.
 *
 * `SharingStarted.WhileSubscribed(5_000)` keeps the upstream
 * collector alive across short configuration changes (rotation)
 * without churning the underlying flow. The repository's
 * [StateFlow] already replays the current value on subscribe, so
 * the screen always renders something on first composition.
 *
 * Mirrors `ChatsFlow` from onym-ios PR #30 (the iOS twin uses an
 * `Observable @MainActor` driver pumping `AsyncStream`; same role,
 * Android-idiomatic types).
 */
class ChatsViewModel(
    private val repository: GroupRepository,
) : ViewModel() {

    val groups: StateFlow<List<ChatGroup>> = repository.snapshots.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repository.snapshots.value,
    )
}
