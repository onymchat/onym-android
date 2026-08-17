package app.onym.android.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityId
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

    /** The create-time offer keys, one per invitee. The shared link is
     *  [state], not a row. This list is how they get retired. */
    private val _otherInvites = MutableStateFlow<List<InviteRow>>(emptyList())
    val otherInvites: StateFlow<List<InviteRow>> = _otherInvites.asStateFlow()

    /** Set while a rotate is in flight so the UI can disable the
     *  button — rotating twice in a row would strand a live key. */
    private val _isRotating = MutableStateFlow(false)
    val isRotating: StateFlow<Boolean> = _isRotating.asStateFlow()

    /**
     * Resolve and surface the group's share link. Idempotent: re-entry
     * returns the same link. Unknown [groupId] flips to [State.Failed].
     */
    /** One revokable invite. [label] is null for a superseded shared
     *  key; the screen supplies the localized name for that case. */
    data class InviteRow(val introPublicKey: ByteArray, val label: String?) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is InviteRow &&
                introPublicKey.contentEquals(other.introPublicKey) &&
                label == other.label)

        override fun hashCode(): Int = 31 * introPublicKey.contentHashCode() + label.hashCode()
    }

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
                refreshOtherInvites(activeIdentityId, group.groupIdBytes)
            } catch (e: Throwable) {
                _state.value = State.Failed(
                    e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * Replace the shared link. Nobody holding the old one is told, so
     * this is the "my link leaked" escape hatch.
     */
    fun rotateLink(groupId: String) {
        if (_isRotating.value) return
        val group = groupRepository.snapshots.value.firstOrNull { it.id == groupId } ?: return
        val activeIdentityId = identity.currentIdentityId.value ?: return
        _isRotating.value = true
        viewModelScope.launch {
            try {
                val capability = introducer.rotate(
                    ownerIdentityId = activeIdentityId,
                    groupId = group.groupIdBytes,
                    groupName = group.name,
                )
                _state.value = State.Ready(
                    link = capability.toAppLink(),
                    groupName = group.name,
                )
            } catch (e: Throwable) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                _isRotating.value = false
            }
        }
    }

    /** Retire one per-invitee offer key. */
    fun revoke(row: InviteRow, groupId: String) {
        val group = groupRepository.snapshots.value.firstOrNull { it.id == groupId } ?: return
        val activeIdentityId = identity.currentIdentityId.value ?: return
        viewModelScope.launch {
            introducer.revoke(row.introPublicKey)
            refreshOtherInvites(activeIdentityId, group.groupIdBytes)
        }
    }

    private suspend fun refreshOtherInvites(ownerIdentityId: IdentityId, groupId: ByteArray) {
        val current = currentIntroPublicKey()
        // Everything but the link on screen. A shared key stranded by a
        // crash mid-rotate would otherwise be listed nowhere.
        _otherInvites.value = introducer.liveInvites(ownerIdentityId, groupId)
            .filter { current == null || !it.introPublicKey.contentEquals(current) }
            .map { InviteRow(it.introPublicKey, it.label) }
    }

    /** Intro pubkey behind the rendered link, so the list excludes it. */
    private fun currentIntroPublicKey(): ByteArray? {
        val link = (_state.value as? State.Ready)?.link ?: return null
        return IntroCapability.fromLink(link)?.introPublicKey
    }
}
