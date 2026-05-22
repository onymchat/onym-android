package app.onym.android.chats

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row shape for one chat message on disk. Splits into plain
 * (queryable, non-identifying) and AES-GCM-encrypted (sensitive)
 * columns — same pattern as [app.onym.android.group.PersistedGroup].
 *
 * Plain columns:
 *  - [id] — UUID string. Stable per message; used for dedup +
 *    `updateStatus` lookups.
 *  - [groupId] — 64-char lowercase hex of the 32-byte group ID;
 *    matches [app.onym.android.group.ChatGroup.id]. Indexed for
 *    the per-group chat-thread query.
 *  - [ownerIdentityId] — [app.onym.android.identity.IdentityId.value].
 *    Indexed for the per-identity filter + cascade delete.
 *  - [sentAt] — wall-clock ms since epoch. Plain INTEGER so the
 *    DAO can `ORDER BY sentAt ASC` without a converter.
 *  - [directionRaw], [statusRaw], [groupTypeRaw] — small enums
 *    persisted as their enum-name / wireValue strings; not
 *    user-identifying.
 *
 * Encrypted columns (`StorageEncryption.encrypt`):
 *  - [encryptedSenderBlsPubkeyHex] — sender identity claim.
 *    Decryption boundary lives in [RoomMessageStore].
 *  - [encryptedBody] — message plaintext.
 *
 * Mirrors `PersistedMessage.swift` from onym-ios PR #148.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["ownerIdentityId"]),
    ],
)
data class PersistedMessage(
    @PrimaryKey val id: String,
    val groupId: String,
    val ownerIdentityId: String,
    val sentAt: Long,
    val directionRaw: String,
    val statusRaw: String,
    val groupTypeRaw: String,
    val encryptedSenderBlsPubkeyHex: ByteArray,
    val encryptedBody: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedMessage) return false
        return id == other.id &&
            groupId == other.groupId &&
            ownerIdentityId == other.ownerIdentityId &&
            sentAt == other.sentAt &&
            directionRaw == other.directionRaw &&
            statusRaw == other.statusRaw &&
            groupTypeRaw == other.groupTypeRaw &&
            encryptedSenderBlsPubkeyHex.contentEquals(other.encryptedSenderBlsPubkeyHex) &&
            encryptedBody.contentEquals(other.encryptedBody)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + groupId.hashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + sentAt.hashCode()
        h = 31 * h + directionRaw.hashCode()
        h = 31 * h + statusRaw.hashCode()
        h = 31 * h + groupTypeRaw.hashCode()
        h = 31 * h + encryptedSenderBlsPubkeyHex.contentHashCode()
        h = 31 * h + encryptedBody.contentHashCode()
        return h
    }
}
