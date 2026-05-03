package chat.onym.android.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-lifetime in-memory [InvitationStore]. Used as the V1
 * default sink for the multi-identity inbox fan-out (PR-4) while the
 * Room-backed receive-and-decrypt flow lands in a follow-up. Swap to
 * [RoomInvitationStore] (already exists, not yet wired) once the
 * receive-side decoding lands.
 *
 * Process death drops everything; that's intentional for V1 — the
 * receive side hasn't stabilised yet, and reading garbage off disk
 * after a schema change would be more confusing than re-fetching
 * from the relay on next launch.
 */
class InMemoryInvitationStore : InvitationStore {

    private val mutex = Mutex()
    private val rows = LinkedHashMap<String, IncomingInvitationRecord>()

    override suspend fun list(): List<IncomingInvitationRecord> = mutex.withLock {
        rows.values.sortedByDescending { it.receivedAt }
    }

    override suspend fun save(record: IncomingInvitationRecord): Boolean = mutex.withLock {
        val isNew = !rows.containsKey(record.id)
        rows[record.id] = record
        isNew
    }

    override suspend fun updateStatus(id: String, status: IncomingInvitationStatus) {
        mutex.withLock {
            val existing = rows[id] ?: return
            rows[id] = existing.copy(status = status)
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock { rows.remove(id) }
    }
}
