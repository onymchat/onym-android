package app.onym.android.chats

import androidx.room.Entity
import androidx.room.Index

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
 *  - [replyToMessageId] — optional UUID string of the replied-to
 *    message. Plain (not sensitive — it's just a pointer to another
 *    row in this same table) and left queryable for a future
 *    "replies to X" lookup. Nullable, so adding it is a non-
 *    destructive `ALTER TABLE ADD COLUMN` (see
 *    [MessageDatabaseMigrations.MIGRATION_1_2]).
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
    // Uniqueness is the COMPOSITE (id, ownerIdentityId), not `id`
    // alone. The wire `messageId` is minted once by the sender and
    // fanned out to every recipient inbox, so when two local
    // identities are both members of a group the same id lands twice —
    // once per identity. Keying on `id` alone made the second arrival's
    // insert a silent no-op (OnConflictStrategy.IGNORE) — the second
    // identity never saw the message. Each identity keeps its own row.
    // Mirrors the composite `#Unique([id, ownerIdentityId])` on iOS
    // `PersistedMessage`.
    primaryKeys = ["id", "ownerIdentityId"],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["ownerIdentityId"]),
    ],
)
data class PersistedMessage(
    /** UUID string of the wire `messageId`. Not unique on its own —
     *  part of the composite primary key with [ownerIdentityId]. */
    val id: String,
    val groupId: String,
    val ownerIdentityId: String,
    val sentAt: Long,
    val directionRaw: String,
    val statusRaw: String,
    val groupTypeRaw: String,
    val encryptedSenderBlsPubkeyHex: ByteArray,
    val encryptedBody: ByteArray,
    val replyToMessageId: String? = null,
    /** AES-GCM-encrypted JSON of the [ChatImageAttachment] (or `null`
     *  for a text-only message). Encrypted at rest like `body` — it
     *  carries the per-image key. Nullable so the migration lands the
     *  column on existing rows without a wipe. */
    val encryptedAttachmentJson: ByteArray? = null,
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
            encryptedBody.contentEquals(other.encryptedBody) &&
            replyToMessageId == other.replyToMessageId &&
            (encryptedAttachmentJson?.contentEquals(other.encryptedAttachmentJson)
                ?: (other.encryptedAttachmentJson == null))
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
        h = 31 * h + (replyToMessageId?.hashCode() ?: 0)
        h = 31 * h + (encryptedAttachmentJson?.contentHashCode() ?: 0)
        return h
    }
}
