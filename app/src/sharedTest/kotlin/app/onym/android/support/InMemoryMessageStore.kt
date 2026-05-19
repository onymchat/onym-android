package app.onym.android.support

import app.onym.android.chats.ChatMessage
import app.onym.android.chats.MessageStatus
import app.onym.android.chats.MessageStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Reusable in-memory [MessageStore]. Same contract as
 * [app.onym.android.chats.RoomMessageStore] — no Room, no
 * StorageEncryption — fast tests of [app.onym.android.chats.MessageRepository]
 * semantics without the persistence-plumbing tax.
 *
 * Mirrors the test fixture pattern established by
 * [InMemoryGroupStore] for the groups domain.
 */
class InMemoryMessageStore : MessageStore {

    private val mutex = Mutex()
    private val rows = LinkedHashMap<String, ChatMessage>()

    suspend fun preload(messages: List<ChatMessage>) = mutex.withLock {
        for (m in messages) rows[m.id.toString()] = m
    }

    override suspend fun listForGroup(
        ownerIdentityId: String,
        groupId: String,
    ): List<ChatMessage> = mutex.withLock {
        rows.values
            .filter { it.ownerIdentityId == ownerIdentityId && it.groupId == groupId }
            .sortedBy { it.sentAtMillis }
    }

    override suspend fun findById(id: UUID): ChatMessage? = mutex.withLock {
        rows[id.toString()]
    }

    override suspend fun insert(message: ChatMessage): Boolean = mutex.withLock {
        val key = message.id.toString()
        if (rows.containsKey(key)) return@withLock false
        rows[key] = message
        true
    }

    override suspend fun updateStatus(id: UUID, status: MessageStatus) {
        mutex.withLock {
            val existing = rows[id.toString()] ?: return@withLock
            rows[id.toString()] = existing.copy(status = status)
        }
    }

    override suspend fun deleteForOwner(ownerIdentityId: String): Int = mutex.withLock {
        val before = rows.size
        rows.values.removeAll { it.ownerIdentityId == ownerIdentityId }
        before - rows.size
    }

    override suspend fun deleteForGroup(groupId: String): Int = mutex.withLock {
        val before = rows.size
        rows.values.removeAll { it.groupId == groupId }
        before - rows.size
    }
}
