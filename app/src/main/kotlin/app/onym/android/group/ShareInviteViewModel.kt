package app.onym.android.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.identity.ActiveIdentityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the post-create "Share invite" surface. Owns one piece of
 * state — the [State.Ready.link] for the just-minted invite — and
 * exposes one intent ([mintFor]) to refresh / re-mint.
 *
 * Why minting is decoupled from the screen's first composition:
 * minting is a side effect (writes to [IntroKeyStore]); doing it
 * in `LaunchedEffect(Unit)` ties it to recomposition timing
 * (config-change reflows would mint twice). The view-model pulls
 * the side-effect off the composition tree where it belongs.
 *
 * Resolves the [ChatGroup] internally from [groupRepository] given
 * a hex group id — this lets the navigation host pass a `String`
 * path arg without having to look the group up itself or thread
 * the value type through Compose nav arguments.
 */
class ShareInviteViewModel(
    private val identity: ActiveIdentityProvider,
    private val introducer: InviteIntroducer,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    sealed class State {
        object Idle : State()
        object Minting : State()
        data class Ready(val link: String, val groupName: String?) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Resolve the share link for the group with hex id [groupId] and
     * surface it. Idempotent: repeated calls (screen re-entry, retry
     * tap) return the *same* link while the group's intro key is
     * inside its 24h [IntroKeyEntry.LIFETIME_MILLIS] window, and only
     * mint a fresh keypair once that key has expired. One link, many
     * joiners — each still gated by an explicit Approve.
     *
     * If [groupId] does not resolve to a local group (race between
     * persistence + navigation, or a stale deeplink back into share)
     * the state flips to [State.Failed] so the UI can render a
     * message + retry button without crashing.
     */
    fun mintFor(groupId: String) {
        val group = groupRepository.snapshots.value.firstOrNull { it.id == groupId }
        if (group == null) {
            _state.value = State.Failed("Group not found on this device")
            return
        }
        val activeIdentityId = identity.currentIdentityId.value
        if (activeIdentityId == null) {
            _state.value = State.Failed("No identity selected")
            return
        }
        _state.value = State.Minting
        viewModelScope.launch {
            try {
                val capability = introducer.currentOrMint(
                    ownerIdentityId = activeIdentityId,
                    groupId = group.groupIdBytes,
                    groupName = group.name,
                )
                _state.value = State.Ready(
                    link = capability.toAppLink(),
                    groupName = group.name,
                )
            } catch (e: Throwable) {
                _state.value = State.Failed(
                    e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }
}
