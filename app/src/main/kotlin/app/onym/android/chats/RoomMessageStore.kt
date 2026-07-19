package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.persistence.StorageEncryption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    private val attachmentJson = Json { ignoreUnknownKeys = true }

    override suspend fun listForGroup(
        ownerIdentityId: String,
        groupId: String,
    ): List<ChatMessage> = withContext(ioDispatcher) {
        dao.listForOwnerAndGroup(ownerIdentityId, groupId).mapNotNull(::decode)
    }

    override suspend fun findById(id: UUID, ownerIdentityId: String): ChatMessage? =
        withContext(ioDispatcher) {
            dao.findByIdAndOwner(id.toString(), ownerIdentityId)?.let(::decode)
        }

    override suspend fun latestMessage(ownerIdentityId: String, groupId: String): ChatMessage? =
        withContext(ioDispatcher) {
            dao.latestForOwnerAndGroup(ownerIdentityId, groupId)?.let(::decode)
        }

    override suspend fun unreadCount(
        ownerIdentityId: String,
        groupId: String,
        sinceMillis: Long,
    ): Int = withContext(ioDispatcher) {
        dao.unreadCount(ownerIdentityId, groupId, sinceMillis)
    }

    override fun changeToken(): kotlinx.coroutines.flow.Flow<Int> =
        dao.observeMessageChangeToken()

    override suspend fun search(
        ownerIdentityId: String,
        query: String,
        limit: Int,
    ): List<ChatMessage> = withContext(ioDispatcher) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withContext emptyList()
        val results = ArrayList<ChatMessage>()
        for (row in dao.listForOwner(ownerIdentityId)) {
            val message = decode(row) ?: continue
            if (message.body.lowercase().contains(needle)) {
                results.add(message)
                if (results.size >= limit) break
            }
        }
        results
    }

    override suspend fun insert(message: ChatMessage): Boolean =
        withContext(ioDispatcher) {
            // OnConflictStrategy.IGNORE returns -1 on a (id, owner)
            // clash — a genuine re-delivery to the same identity. A
            // different identity's copy is a distinct composite key and
            // inserts cleanly.
            dao.insert(encode(message)) != -1L
        }

    override suspend fun updateStatus(id: UUID, ownerIdentityId: String, status: MessageStatus) {
        withContext(ioDispatcher) { dao.updateStatus(id.toString(), ownerIdentityId, status.name) }
    }

    override suspend fun deleteById(id: UUID, ownerIdentityId: String): Int =
        withContext(ioDispatcher) { dao.deleteByIdAndOwner(id.toString(), ownerIdentityId) }

    override suspend fun deleteForOwner(ownerIdentityId: String): Int =
        withContext(ioDispatcher) { dao.deleteForOwner(ownerIdentityId) }

    override suspend fun deleteForGroup(groupId: String, ownerIdentityId: String): Int =
        withContext(ioDispatcher) { dao.deleteForGroup(groupId, ownerIdentityId) }

    override suspend fun deleteAll(): Int =
        withContext(ioDispatcher) { dao.deleteAll() }

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
        // Plain pointer to another row — not sensitive, so no
        // encryption. Stored as the UUID string; null for a non-reply.
        replyToMessageId = message.replyToMessageId?.toString(),
        // Attachment JSON is encrypted at rest (carries the per-image key).
        encryptedAttachmentJson = message.imageAttachment?.let {
            encryption.encrypt(attachmentJson.encodeToString(ChatImageAttachment.serializer(), it))
        },
        // Video attachment JSON is encrypted at rest (carries the
        // per-video key + the poster descriptor).
        encryptedVideoAttachmentJson = message.videoAttachment?.let {
            encryption.encrypt(attachmentJson.encodeToString(ChatVideoAttachment.serializer(), it))
        },
        // Album JSON (list of image/video items), encrypted at rest.
        encryptedAlbumJson = message.albumAttachments?.let {
            encryption.encrypt(attachmentJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChatMediaAttachment.serializer()), it
            ))
        },
        // Voice JSON (carries the per-clip key + waveform), encrypted at rest.
        encryptedVoiceAttachmentJson = message.voiceAttachment?.let {
            encryption.encrypt(attachmentJson.encodeToString(ChatVoiceAttachment.serializer(), it))
        },
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
        // A malformed reply pointer (somehow non-UUID) degrades to no
        // reply rather than dropping the whole row — the message body
        // is the important part; the quote just won't render.
        val replyToMessageId = row.replyToMessageId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        // Attachment is advisory: a decrypt/decode miss degrades to a
        // text-only row rather than dropping the whole message.
        val imageAttachment = row.encryptedAttachmentJson
            ?.let { tryDecryptString(it) }
            ?.let {
                runCatching {
                    attachmentJson.decodeFromString(ChatImageAttachment.serializer(), it)
                }.getOrNull()
            }
        val videoAttachment = row.encryptedVideoAttachmentJson
            ?.let { tryDecryptString(it) }
            ?.let {
                runCatching {
                    attachmentJson.decodeFromString(ChatVideoAttachment.serializer(), it)
                }.getOrNull()
            }
        val albumAttachments = row.encryptedAlbumJson
            ?.let { tryDecryptString(it) }
            ?.let {
                runCatching {
                    attachmentJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ChatMediaAttachment.serializer()), it
                    )
                }.getOrNull()
            }
        val voiceAttachment = row.encryptedVoiceAttachmentJson
            ?.let { tryDecryptString(it) }
            ?.let {
                runCatching {
                    attachmentJson.decodeFromString(ChatVoiceAttachment.serializer(), it)
                }.getOrNull()
            }
        return ChatMessage(
            id = id,
            groupId = row.groupId,
            ownerIdentityId = row.ownerIdentityId,
            senderBlsPubkeyHex = senderHex,
            body = body,
            sentAtMillis = row.sentAt,
            direction = direction,
            status = status,
            replyToMessageId = replyToMessageId,
            groupType = groupType,
            imageAttachment = imageAttachment,
            videoAttachment = videoAttachment,
            albumAttachments = albumAttachments,
            voiceAttachment = voiceAttachment,
        )
    }

    private fun tryDecryptString(bytes: ByteArray): String? = try {
        encryption.decryptString(bytes)
    } catch (_: Throwable) { null }
}
