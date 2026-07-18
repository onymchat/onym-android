package app.onym.android.chats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO over [PersistedMessage]. Mirrors the verb set the chat
 * surface needs: per-(owner, group) list for the chat thread,
 * insert, status update on the hot path, plus the cascade-delete
 * variants for identity removal and group deletion.
 *
 * Suspend throughout; Room's default single-threaded executor
 * handles the volume (messages arrive at human typing / receive
 * speed).
 *
 * Mirrors the SwiftData fetch / save calls in
 * `SwiftDataMessageStore` from onym-ios PR #148.
 */
@Dao
interface MessageDao {

    /** Chat-thread read: per identity, per group, oldest first.
     *  The composite (owner, group) filter rides on the two
     *  separate indices defined in [PersistedMessage] — Room picks
     *  one and post-filters; the volumes don't justify a composite
     *  index yet. */
    @Query(
        "SELECT * FROM messages " +
            "WHERE ownerIdentityId = :ownerIdentityId AND groupId = :groupId " +
            "ORDER BY sentAt ASC",
    )
    suspend fun listForOwnerAndGroup(
        ownerIdentityId: String,
        groupId: String,
    ): List<PersistedMessage>

    /** Single-message lookup scoped to the composite
     *  `(id, ownerIdentityId)` key so one identity's copy is never
     *  returned in place of another's. */
    @Query("SELECT * FROM messages WHERE id = :id AND ownerIdentityId = :ownerIdentityId LIMIT 1")
    suspend fun findByIdAndOwner(id: String, ownerIdentityId: String): PersistedMessage?

    /** Idempotent on [PersistedMessage.id]: a re-delivery of the
     *  same wire message is a silent no-op rather than a thrown
     *  exception, mirroring the dispatcher's "drop duplicates"
     *  contract. Returns the row id on insert and `-1` when the
     *  conflict strategy ignored the write — callers expose that as
     *  `Boolean` at the [MessageStore] seam. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: PersistedMessage): Long

    /** Hot path: flip `PENDING` → `SENT` / `FAILED` (or any other
     *  status transition) without re-encrypting the body. Skipping
     *  the encryption round-trip matters because send-confirmation
     *  callbacks fire on every relay ACK. Returns the row count. */
    @Query("UPDATE messages SET statusRaw = :statusRaw WHERE id = :id AND ownerIdentityId = :ownerIdentityId")
    suspend fun updateStatus(id: String, ownerIdentityId: String, statusRaw: String): Int

    /** Cascade delete for the identity-removal flow. Returns the
     *  number of rows deleted so the caller can log cleanup size. */
    @Query("DELETE FROM messages WHERE ownerIdentityId = :ownerIdentityId")
    suspend fun deleteForOwner(ownerIdentityId: String): Int

    /** Cascade delete for the group-deletion flow (a user nukes a
     *  group → its messages go with it). Scoped to the owner so
     *  deleting a chat for one identity leaves another identity's copy
     *  of the same group intact. Returns the number of rows deleted. */
    @Query("DELETE FROM messages WHERE groupId = :groupId AND ownerIdentityId = :ownerIdentityId")
    suspend fun deleteForGroup(groupId: String, ownerIdentityId: String): Int
}
