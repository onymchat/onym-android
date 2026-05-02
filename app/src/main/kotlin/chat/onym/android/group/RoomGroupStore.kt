package chat.onym.android.group

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import chat.onym.android.persistence.StorageEncryption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [GroupStore] backed by Room + [StorageEncryption]. Wraps the
 * sensitive columns with AES-GCM on the way in, unwraps on the way
 * out — the encryption is invisible to callers.
 *
 * IO happens on [ioDispatcher]; default is [Dispatchers.IO]. Tests
 * pass `UnconfinedTestDispatcher` (or rely on
 * `Room.allowMainThreadQueries()`).
 *
 * Mirrors `SwiftDataGroupStore` from onym-ios PR #25.
 */
class RoomGroupStore(
    private val dao: GroupDao,
    private val encryption: StorageEncryption,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GroupStore {

    override suspend fun list(): List<ChatGroup> = withContext(ioDispatcher) {
        dao.list().mapNotNull(::decode)
    }

    override suspend fun insertOrUpdate(group: ChatGroup): Boolean = withContext(ioDispatcher) {
        val encoded = encode(group)
        // Pre-check via findById so we can honour the
        // "true on insert, false on update" contract precisely;
        // OnConflictStrategy.REPLACE alone would lose the original
        // row's createdAt on update conflicts.
        val existing = dao.findById(group.id)
        if (existing == null) {
            dao.insert(encoded)
            true
        } else {
            // Preserve the original createdAt on update — only the
            // mutable columns (epoch, commitment, isPublished, name,
            // members, etc.) get refreshed. Mirrors the iOS twin's
            // explicit field-by-field assignment in `insertOrUpdate`.
            dao.update(encoded.copy(createdAt = existing.createdAt))
            false
        }
    }

    override suspend fun markPublished(id: String, commitment: ByteArray?) {
        withContext(ioDispatcher) {
            if (commitment != null) {
                dao.markPublishedWithCommitment(id, encryption.encrypt(commitment))
            } else {
                dao.markPublishedFlagOnly(id)
            }
        }
    }

    override suspend fun delete(id: String) {
        withContext(ioDispatcher) { dao.delete(id) }
    }

    // ─── encode / decode boundary ──────────────────────────────────

    private fun encode(group: ChatGroup): PersistedGroup {
        val membersJson = jsonFormat.encodeToString(membersSerializer, group.members)
        return PersistedGroup(
            id = group.id,
            createdAt = group.createdAtMillis,
            // ULong → Long via bit pattern preserves all 64 bits;
            // domain layer does the inverse via toULong() when reading.
            epoch = group.epoch.toLong(),
            tierRaw = group.tier.rawValue,
            groupTypeRaw = group.groupType.rawValue.toInt(),
            isPublishedOnChain = group.isPublishedOnChain,
            encryptedName = encryption.encrypt(group.name),
            encryptedGroupSecret = encryption.encrypt(group.groupSecret),
            encryptedMembersJson = encryption.encrypt(membersJson.toByteArray(Charsets.UTF_8)),
            encryptedSalt = encryption.encrypt(group.salt),
            encryptedCommitment = group.commitment?.let(encryption::encrypt),
            encryptedAdminPubkeyHex = group.adminPubkeyHex?.let(encryption::encrypt),
        )
    }

    /** Tolerant decode: a row whose encrypted columns fail to
     *  decrypt or whose enum rawValues don't resolve is skipped
     *  rather than crashing the whole list read. Mirrors the
     *  `guard let … else { return nil }` ladder in iOS's `decode`. */
    private fun decode(row: PersistedGroup): ChatGroup? {
        val name = tryDecryptString(row.encryptedName) ?: return null
        val groupSecret = tryDecrypt(row.encryptedGroupSecret) ?: return null
        val membersJsonBytes = tryDecrypt(row.encryptedMembersJson) ?: return null
        val members = try {
            jsonFormat.decodeFromString(
                membersSerializer,
                membersJsonBytes.toString(Charsets.UTF_8),
            )
        } catch (_: SerializationException) {
            return null
        }
        val salt = tryDecrypt(row.encryptedSalt) ?: return null
        val tier = SepTier.entries.firstOrNull { it.rawValue == row.tierRaw } ?: return null
        val groupType = try {
            SepGroupType.fromRaw(row.groupTypeRaw.toUInt())
        } catch (_: IllegalArgumentException) {
            return null
        }
        val commitment = row.encryptedCommitment?.let { tryDecrypt(it) }
        val adminPubkeyHex = row.encryptedAdminPubkeyHex?.let { tryDecryptString(it) }

        return ChatGroup(
            id = row.id,
            name = name,
            groupSecret = groupSecret,
            createdAtMillis = row.createdAt,
            members = members,
            // Long → ULong via bit pattern recovers the original
            // 64-bit value. Serializer on SepPublicInputs uses ULong
            // when this round-trips back to JSON.
            epoch = row.epoch.toULong(),
            salt = salt,
            commitment = commitment,
            tier = tier,
            groupType = groupType,
            adminPubkeyHex = adminPubkeyHex,
            isPublishedOnChain = row.isPublishedOnChain,
        )
    }

    private fun tryDecrypt(bytes: ByteArray): ByteArray? = try {
        encryption.decrypt(bytes)
    } catch (_: Throwable) { null }

    private fun tryDecryptString(bytes: ByteArray): String? = try {
        encryption.decryptString(bytes)
    } catch (_: Throwable) { null }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val membersSerializer = ListSerializer(GovernanceMember.serializer())
    }
}
