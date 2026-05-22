package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.persistence.StorageEncryption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [MessageStore] backed by Room + [StorageEncryption]. Wraps the
 * sensitive columns with AES-GCM on the way in, unwraps on the way
 * out — the encryption is invisible to callers.
 *
 * IO happens on [ioDispatcher]; default is [Dispatchers.IO]. Tests
 * pass `UnconfinedTestDispatcher` or rely on
 * `Room.allowMainThreadQueries()`.
 *
 * Mirrors `SwiftDataMessageStore` from onym-ios PR #148.
 */
class RoomMessageStore(
    private val dao: MessageDao,
    private val encryption: StorageEncryption,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MessageStore {

    override suspend fun listForGroup(
        ownerIdentityId: String,
        groupId: String,
    ): List<ChatMessage> = withContext(ioDispatcher) {
        dao.listForOwnerAndGroup(ownerIdentityId, groupId).mapNotNull(::decode)
    }

    override suspend fun insert(message: ChatMessage) {
        withContext(ioDispatcher) { dao.insert(encode(message)) }
    }

    override suspend fun updateStatus(id: UUID, status: MessageStatus) {
        withContext(ioDispatcher) { dao.updateStatus(id.toString(), status.name) }
    }

    override suspend fun deleteForOwner(ownerIdentityId: String): Int =
        withContext(ioDispatcher) { dao.deleteForOwner(ownerIdentityId) }

    override suspend fun deleteForGroup(groupId: String): Int =
        withContext(ioDispatcher) { dao.deleteForGroup(groupId) }

    // ─── encode / decode boundary ──────────────────────────────────

    private fun encode(message: ChatMessage): PersistedMessage = PersistedMessage(
        id = message.id.toString(),
        groupId = message.groupId,
        ownerIdentityId = message.ownerIdentityId,
        sentAt = message.sentAtMillis,
        directionRaw = message.direction.name,
        statusRaw = message.status.name,
        groupTypeRaw = message.groupType.wireValue,
        encryptedSenderBlsPubkeyHex = encryption.encrypt(message.senderBlsPubkeyHex),
        encryptedBody = encryption.encrypt(message.body),
    )

    /** Tolerant decode: a row whose encrypted columns fail to
     *  decrypt or whose enum string doesn't resolve is skipped
     *  rather than crashing the whole list read. Mirrors the
     *  `guard let … else { return nil }` ladder in iOS's `decode`. */
    private fun decode(row: PersistedMessage): ChatMessage? {
        val senderHex = tryDecryptString(row.encryptedSenderBlsPubkeyHex) ?: return null
        val body = tryDecryptString(row.encryptedBody) ?: return null
        val direction = MessageDirection.entries.firstOrNull { it.name == row.directionRaw }
            ?: return null
        val status = MessageStatus.entries.firstOrNull { it.name == row.statusRaw }
            ?: return null
        val groupType = SepGroupType.fromWire(row.groupTypeRaw) ?: return null
        val id = try { UUID.fromString(row.id) } catch (_: Throwable) { return null }
        return ChatMessage(
            id = id,
            groupId = row.groupId,
            ownerIdentityId = row.ownerIdentityId,
            senderBlsPubkeyHex = senderHex,
            body = body,
            sentAtMillis = row.sentAt,
            direction = direction,
            status = status,
            groupType = groupType,
        )
    }

    private fun tryDecryptString(bytes: ByteArray): String? = try {
        encryption.decryptString(bytes)
    } catch (_: Throwable) { null }
}
