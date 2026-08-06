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

    /** Other live invites for this group — the create-time offer keys,
     *  each aimed at one invitee. The group's shared link is [state],
     *  not a row here. Empty for a group created without invitees.
     *
     *  Nothing expires any more, so this list is the only way these
     *  ever get retired. */
    private val _otherInvites = MutableStateFlow<List<InviteRow>>(emptyList())
    val otherInvites: StateFlow<List<InviteRow>> = _otherInvites.asStateFlow()

    /** Set while a rotate is in flight so the UI can disable the
     *  button — rotating twice in a row would strand a live key. */
    private val _isRotating = MutableStateFlow(false)
    val isRotating: StateFlow<Boolean> = _isRotating.asStateFlow()

    /**
     * Resolve the share link for the group with hex id [groupId] and
     * surface it. Idempotent: repeated calls (screen re-entry, retry
     * tap) return the *same* link for as long as the group has one.
     * Links do not expire; "Generate new link" is what replaces one.
     * One link, many joiners — each still gated by an explicit Approve.
     *
     * If [groupId] does not resolve to a local group (race between
     * persistence + navigation, or a stale deeplink back into share)
     * the state flips to [State.Failed] so the UI can render a
     * message + retry button without crashing.
     */
    /** One revokable invite on the list. [introPublicKey] is the
     *  revoke handle; it never reaches the screen. */
    data class InviteRow(val introPublicKey: ByteArray, val label: String) {
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
     * Replace the group's shared link. The old one stops working the
     * moment the intro pump drops its subscription — there is no way
     * to notify anyone already holding it, so this is the "my link
     * leaked" escape hatch, not a polite hand-off.
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
        // `label == null` is the group's shared link, which the screen
        // already renders as the QR + link; only the named per-invitee
        // offers belong on the list.
        _otherInvites.value = introducer.liveInvites(ownerIdentityId, groupId)
            .mapNotNull { entry ->
                entry.label?.let { InviteRow(entry.introPublicKey, it) }
            }
    }
}
