package app.onym.android.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupAvatarBroadcaster
import app.onym.android.group.GroupRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row on the Chats list: a group enriched with its latest-message
 * preview + unread count, used for the subtitle + badge and to order the
 * list most-recent-message-first. Mirrors iOS `ChatListItem`.
 */
data class ChatListItem(
    val group: ChatGroup,
    /** Latest message's one-line preview (subtitle), or `null` when the
     *  group has no messages yet. */
    val latestPreview: String?,
    /** Latest message timestamp (ms) — the sort key; falls back to the
     *  group's creation time when there are no messages. */
    val latestAtMillis: Long?,
    /** Incoming messages received since the thread was last read. */
    val unreadCount: Int,
) {
    val id: String get() = group.id
    val sortKey: Long get() = latestAtMillis ?: group.createdAtMillis
}

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
 *
 * The single mutating intent — the admin's group-photo change — is
 * delegated to [GroupAvatarBroadcaster] (apply locally + fan out). The
 * row UI itself stays read-only; [groups] re-emits once the change
 * persists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModel(
    private val repository: GroupRepository,
    private val messageRepository: MessageRepository,
    private val avatarBroadcaster: GroupAvatarBroadcaster? = null,
) : ViewModel() {

    /**
     * Enriched + sorted chat-list rows. Recomputes whenever the group set
     * changes OR the messages table changes (via
     * [MessageRepository.changeToken]) — so a new/received message re-sorts
     * the list and updates the subtitle + unread badge live. Sorted
     * most-recent-message-first.
     */
    val items: StateFlow<List<ChatListItem>> =
        combine(repository.snapshots, messageRepository.changeToken()) { groups, _ -> groups }
            .mapLatest { groups -> enrich(groups) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    private suspend fun enrich(groups: List<ChatGroup>): List<ChatListItem> =
        groups.map { group ->
            val latest = messageRepository.latestMessage(group.ownerIdentityId, group.id)
            val unread = messageRepository.unreadCount(
                group.ownerIdentityId, group.id, group.lastReadAtMillis ?: 0L
            )
            ChatListItem(
                group = group,
                latestPreview = latest?.chatListPreview,
                latestAtMillis = latest?.sentAtMillis,
                unreadCount = unread,
            )
        }.sortedByDescending { it.sortKey }

    /**
     * Delete a chat from this device: wipe its message thread, then remove
     * the group — both scoped to the owning identity, so another identity's
     * copy of the same group is untouched. Local-only: the group may still
     * exist on-chain, but this device's copy (and every message in it) is
     * gone. Both mutations re-emit snapshots / fire the message-change
     * token, so [items] recomputes and the row disappears without extra
     * plumbing. Mirrors iOS `ChatsFlow.deleteChat`.
     */
    fun deleteChat(groupId: String) {
        val owner = repository.snapshots.value
            .firstOrNull { it.id == groupId }?.ownerIdentityId ?: return
        viewModelScope.launch {
            messageRepository.removeForGroup(groupId, owner)
            repository.delete(groupId, owner)
        }
    }

    /**
     * Admin-only: set ([avatar] non-null) or clear ([avatar] == null)
     * the group photo and broadcast the change. No-op when the
     * broadcaster wasn't wired (e.g. lightweight test/preview VMs). The
     * broadcaster itself enforces the admin gate; non-admin callers are
     * dropped there.
     */
    fun setGroupAvatar(groupId: String, avatar: ByteArray?) {
        val broadcaster = avatarBroadcaster ?: return
        viewModelScope.launch {
            broadcaster.setAvatar(groupId, avatar)
        }
    }

    /**
     * Admin-only: rename the group and broadcast the change. No-op when
     * the broadcaster wasn't wired. The broadcaster enforces the admin
     * gate + trims/ignores a blank name.
     */
    fun setGroupName(groupId: String, name: String) {
        val broadcaster = avatarBroadcaster ?: return
        viewModelScope.launch {
            broadcaster.setName(groupId, name)
        }
    }
}
