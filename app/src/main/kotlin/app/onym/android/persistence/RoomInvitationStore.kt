package app.onym.android.persistence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * [InvitationStore] backed by Room + [StorageEncryption]. Wraps the
 * payload with AES-GCM on the way in, unwraps on the way out — the
 * encryption is invisible to callers.
 *
 * IO happens on [ioDispatcher]; default is [Dispatchers.IO]. Tests
 * pass `UnconfinedTestDispatcher` for deterministic ordering.
 */
class RoomInvitationStore(
    private val dao: InvitationDao,
    private val encryption: StorageEncryption,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InvitationStore {

    override suspend fun list(): List<IncomingInvitationRecord> = withContext(ioDispatcher) {
        dao.list().map { row ->
            IncomingInvitationRecord(
                id = row.id,
                payload = encryption.decrypt(row.encryptedPayload),
                receivedAt = Instant.ofEpochMilli(row.receivedAt),
                status = parseStatus(row.statusRaw),
                ownerIdentityIdString = row.ownerIdentityIdString,
            )
        }
    }

    override suspend fun save(record: IncomingInvitationRecord): Boolean = withContext(ioDispatcher) {
        val rowId = dao.insert(
            PersistedInvitation(
                id = record.id,
                encryptedPayload = encryption.encrypt(record.payload),
                receivedAt = record.receivedAt.toEpochMilli(),
                statusRaw = record.status.name,
                ownerIdentityIdString = record.ownerIdentityIdString,
            )
        )
        rowId != -1L
    }

    override suspend fun updateStatus(id: String, status: IncomingInvitationStatus) {
        withContext(ioDispatcher) { dao.updateStatus(id, status.name) }
    }

    override suspend fun delete(id: String) {
        withContext(ioDispatcher) { dao.delete(id) }
    }

    override suspend fun deleteForOwner(ownerIdentityIdString: String): Int =
        withContext(ioDispatcher) { dao.deleteForOwner(ownerIdentityIdString) }

    /** Tolerant parse: an unknown enum value (from a future schema
     *  rolled back, say) falls back to [IncomingInvitationStatus.Pending]
     *  rather than crashing the read. */
    private fun parseStatus(raw: String): IncomingInvitationStatus =
        IncomingInvitationStatus.entries.firstOrNull { it.name == raw }
            ?: IncomingInvitationStatus.Pending
}
