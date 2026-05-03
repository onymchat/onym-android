package chat.onym.android.support

import chat.onym.android.persistence.IncomingInvitationRecord
import chat.onym.android.persistence.IncomingInvitationStatus
import chat.onym.android.persistence.InvitationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [InvitationStore] for repository tests. Same contract as
 * [chat.onym.android.persistence.RoomInvitationStore], no Room / no
 * Robolectric / no SQLite — ~10× faster per test, lets repository
 * tests focus on contract behaviour instead of CRUD plumbing.
 *
 * The Room-backed impl has its own [chat.onym.android.persistence.RoomInvitationStoreTest]
 * exercising the same seam contract against the real backend; this
 * fake covers the rest.
 *
 * Mirrors `InMemoryInvitationStore.swift` from onym-ios PR #16.
 */
class InMemoryInvitationStore : InvitationStore {

    private val mutex = Mutex()
    private val storage = LinkedHashMap<String, IncomingInvitationRecord>()

    override suspend fun list(): List<IncomingInvitationRecord> = mutex.withLock {
        storage.values.sortedByDescending { it.receivedAt }
    }

    override suspend fun save(record: IncomingInvitationRecord): Boolean = mutex.withLock {
        if (storage.containsKey(record.id)) {
            false
        } else {
            storage[record.id] = record
            true
        }
    }

    override suspend fun updateStatus(id: String, status: IncomingInvitationStatus) {
        mutex.withLock {
            storage[id]?.let { storage[id] = it.copy(status = status) }
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock { storage.remove(id) }
    }

    override suspend fun deleteForOwner(ownerIdentityIdString: String): Int = mutex.withLock {
        val toRemove = storage.values
            .filter { it.ownerIdentityIdString == ownerIdentityIdString }
            .map { it.id }
        for (id in toRemove) storage.remove(id)
        toRemove.size
    }
}
