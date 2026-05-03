package chat.onym.android.support

import chat.onym.android.group.ChatGroup
import chat.onym.android.group.GroupStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable in-memory [GroupStore]. Same contract as
 * [chat.onym.android.group.RoomGroupStore] — no Room, no
 * StorageEncryption — fast tests of `GroupRepository` and
 * `CreateGroupInteractor` semantics without the persistence-plumbing
 * tax.
 *
 * Promoted to `support/` once PR-C grew a second consumer; the iOS
 * twin's `InMemoryGroupStore` made the same hop in PR #26 (mentioned
 * in the comment-of-intent on PR-B's private fixture).
 */
class InMemoryGroupStore : GroupStore {

    private val mutex = Mutex()
    private val rows = LinkedHashMap<String, ChatGroup>()

    suspend fun preload(groups: List<ChatGroup>) = mutex.withLock {
        for (g in groups) rows[g.id] = g
    }

    override suspend fun list(): List<ChatGroup> = mutex.withLock {
        rows.values.sortedByDescending { it.createdAtMillis }
    }

    override suspend fun listForOwner(ownerIdentityId: String): List<ChatGroup> = mutex.withLock {
        rows.values
            .filter { it.ownerIdentityId == ownerIdentityId }
            .sortedByDescending { it.createdAtMillis }
    }

    override suspend fun deleteForOwner(ownerIdentityId: String): Int = mutex.withLock {
        val before = rows.size
        rows.values.removeAll { it.ownerIdentityId == ownerIdentityId }
        before - rows.size
    }

    override suspend fun insertOrUpdate(group: ChatGroup): Boolean = mutex.withLock {
        val isNew = !rows.containsKey(group.id)
        rows[group.id] = group
        isNew
    }

    override suspend fun markPublished(id: String, commitment: ByteArray?) = mutex.withLock {
        val existing = rows[id] ?: return@withLock
        rows[id] = existing.copy(
            isPublishedOnChain = true,
            commitment = commitment ?: existing.commitment,
        )
    }

    override suspend fun delete(id: String) {
        mutex.withLock { rows.remove(id) }
    }
}
