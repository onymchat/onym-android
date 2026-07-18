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
    // Keyed by the composite (id, ownerIdentityId) so the same wire
    // message fanned out to two local identities keeps a row per
    // identity — matches the production Room composite primary key.
    private val rows = LinkedHashMap<Pair<String, String>, ChatMessage>()

    private fun keyOf(message: ChatMessage) = message.id.toString() to message.ownerIdentityId

    suspend fun preload(messages: List<ChatMessage>) = mutex.withLock {
        for (m in messages) rows[keyOf(m)] = m
    }

    override suspend fun listForGroup(
        ownerIdentityId: String,
        groupId: String,
    ): List<ChatMessage> = mutex.withLock {
        rows.values
            .filter { it.ownerIdentityId == ownerIdentityId && it.groupId == groupId }
            .sortedBy { it.sentAtMillis }
    }

    override suspend fun findById(id: UUID, ownerIdentityId: String): ChatMessage? = mutex.withLock {
        rows[id.toString() to ownerIdentityId]
    }

    override suspend fun insert(message: ChatMessage): Boolean = mutex.withLock {
        val key = keyOf(message)
        if (rows.containsKey(key)) return@withLock false
        rows[key] = message
        true
    }

    override suspend fun updateStatus(id: UUID, ownerIdentityId: String, status: MessageStatus) {
        mutex.withLock {
            val key = id.toString() to ownerIdentityId
            val existing = rows[key] ?: return@withLock
            rows[key] = existing.copy(status = status)
        }
    }

    override suspend fun deleteForOwner(ownerIdentityId: String): Int = mutex.withLock {
        val before = rows.size
        rows.values.removeAll { it.ownerIdentityId == ownerIdentityId }
        before - rows.size
    }

    override suspend fun deleteForGroup(groupId: String, ownerIdentityId: String): Int = mutex.withLock {
        val before = rows.size
        rows.values.removeAll { it.groupId == groupId && it.ownerIdentityId == ownerIdentityId }
        before - rows.size
    }
}
