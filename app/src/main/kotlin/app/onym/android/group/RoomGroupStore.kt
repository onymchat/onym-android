package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.persistence.StorageEncryption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    override suspend fun listForOwner(ownerIdentityId: String): List<ChatGroup> =
        withContext(ioDispatcher) {
            dao.listForOwner(ownerIdentityId).mapNotNull(::decode)
        }

    override suspend fun deleteForOwner(ownerIdentityId: String): Int =
        withContext(ioDispatcher) {
            dao.deleteForOwner(ownerIdentityId)
        }

    override suspend fun insertOrUpdate(group: ChatGroup): Boolean = withContext(ioDispatcher) {
        val encoded = encode(group)
        // Pre-check via findByIdAndOwner so we can honour the
        // "true on insert, false on update" contract precisely;
        // OnConflictStrategy.REPLACE alone would lose the original
        // row's createdAt on update conflicts. Scoped to the owner so
        // a second identity joining the same group id inserts its own
        // row instead of overwriting the first identity's.
        val existing = dao.findByIdAndOwner(group.id, group.ownerIdentityId)
        if (existing == null) {
            dao.insert(encoded)
            true
        } else {
            // Preserve the original createdAt AND the last-read marker on
            // update — only the mutable content columns (epoch, commitment,
            // isPublished, name, members, etc.) get refreshed. A group
            // update (member change, avatar, publish) must not reset the
            // user's read state (only markRead writes it). Mirrors the iOS
            // twin's explicit field-by-field assignment in `insertOrUpdate`.
            dao.update(encoded.copy(
                createdAt = existing.createdAt,
                lastReadAtMillis = existing.lastReadAtMillis,
            ))
            false
        }
    }

    override suspend fun markPublished(id: String, ownerIdentityId: String, commitment: ByteArray?) {
        withContext(ioDispatcher) {
            if (commitment != null) {
                dao.markPublishedWithCommitment(id, ownerIdentityId, encryption.encrypt(commitment))
            } else {
                dao.markPublishedFlagOnly(id, ownerIdentityId)
            }
        }
    }

    override suspend fun markRead(id: String, ownerIdentityId: String, lastReadAtMillis: Long) {
        withContext(ioDispatcher) { dao.markRead(id, ownerIdentityId, lastReadAtMillis) }
    }

    override suspend fun delete(id: String, ownerIdentityId: String) {
        withContext(ioDispatcher) { dao.delete(id, ownerIdentityId) }
    }

    // ─── encode / decode boundary ──────────────────────────────────

    private fun encode(group: ChatGroup): PersistedGroup {
        val membersJson = jsonFormat.encodeToString(membersSerializer, group.members)
        val memberProfilesEncrypted: ByteArray? = if (group.memberProfiles.isEmpty()) {
            null
        } else {
            val json = jsonFormat.encodeToString(memberProfilesSerializer, group.memberProfiles)
            encryption.encrypt(json.toByteArray(Charsets.UTF_8))
        }
        return PersistedGroup(
            id = group.id,
            createdAt = group.createdAtMillis,
            // ULong → Long via bit pattern preserves all 64 bits;
            // domain layer does the inverse via toULong() when reading.
            epoch = group.epoch.toLong(),
            tierRaw = group.tier.rawValue,
            groupTypeRaw = group.groupType.wireValue,
            isPublishedOnChain = group.isPublishedOnChain,
            ownerIdentityId = group.ownerIdentityId,
            encryptedName = encryption.encrypt(group.name),
            encryptedGroupSecret = encryption.encrypt(group.groupSecret),
            encryptedMembersJson = encryption.encrypt(membersJson.toByteArray(Charsets.UTF_8)),
            encryptedSalt = encryption.encrypt(group.salt),
            encryptedCommitment = group.commitment?.let(encryption::encrypt),
            encryptedAdminPubkeyHex = group.adminPubkeyHex?.let(encryption::encrypt),
            encryptedMemberProfilesJson = memberProfilesEncrypted,
            encryptedAdminEd25519PubkeyHex = group.adminEd25519PubkeyHex?.let(encryption::encrypt),
            encryptedAvatar = group.avatar?.let(encryption::encrypt),
            lastReadAtMillis = group.lastReadAtMillis,
            encryptedInvitationMessage = group.invitationMessage?.let(encryption::encrypt),
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
        val groupType = SepGroupType.fromWire(row.groupTypeRaw) ?: return null
        val commitment = row.encryptedCommitment?.let { tryDecrypt(it) }
        val adminPubkeyHex = row.encryptedAdminPubkeyHex?.let { tryDecryptString(it) }
        val adminEd25519PubkeyHex = row.encryptedAdminEd25519PubkeyHex?.let { tryDecryptString(it) }
        val avatar = row.encryptedAvatar?.let { tryDecrypt(it) }
        val invitationMessage = row.encryptedInvitationMessage?.let { tryDecryptString(it) }
        val memberProfiles: Map<String, MemberProfile> = row.encryptedMemberProfilesJson
            ?.let { tryDecrypt(it) }
            ?.let { bytes ->
                try {
                    jsonFormat.decodeFromString(
                        memberProfilesSerializer,
                        bytes.toString(Charsets.UTF_8),
                    )
                } catch (_: SerializationException) {
                    emptyMap()
                }
            }
            ?: emptyMap()

        return ChatGroup(
            id = row.id,
            name = name,
            groupSecret = groupSecret,
            createdAtMillis = row.createdAt,
            members = members,
            memberProfiles = memberProfiles,
            // Long → ULong via bit pattern recovers the original
            // 64-bit value. Serializer on SepPublicInputs uses ULong
            // when this round-trips back to JSON.
            epoch = row.epoch.toULong(),
            salt = salt,
            commitment = commitment,
            tier = tier,
            groupType = groupType,
            adminPubkeyHex = adminPubkeyHex,
            adminEd25519PubkeyHex = adminEd25519PubkeyHex,
            isPublishedOnChain = row.isPublishedOnChain,
            ownerIdentityId = row.ownerIdentityId,
            avatar = avatar,
            lastReadAtMillis = row.lastReadAtMillis,
            invitationMessage = invitationMessage,
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
        private val memberProfilesSerializer = MapSerializer(
            String.serializer(),
            MemberProfile.serializer(),
        )
    }
}
