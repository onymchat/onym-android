package app.onym.android.chats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /** All of one identity's messages across every group, newest first.
     *  Bodies are encrypted, so text filtering happens in the store after
     *  decrypt — this just scopes + orders the candidate rows. */
    @Query("SELECT * FROM messages WHERE ownerIdentityId = :ownerIdentityId ORDER BY sentAt DESC")
    suspend fun listForOwner(ownerIdentityId: String): List<PersistedMessage>

    /** Most recent message (any direction) in one group for the chat-list
     *  row subtitle + most-recent-first sort. Body is encrypted, so the
     *  store decrypts the returned row. */
    @Query(
        "SELECT * FROM messages " +
            "WHERE ownerIdentityId = :ownerIdentityId AND groupId = :groupId " +
            "ORDER BY sentAt DESC LIMIT 1",
    )
    suspend fun latestForOwnerAndGroup(
        ownerIdentityId: String,
        groupId: String,
    ): PersistedMessage?

    /** Count of *incoming* messages in one group received after
     *  [sinceMillis] — the chat-list unread badge. Plain columns only, so
     *  no decryption. */
    @Query(
        "SELECT COUNT(*) FROM messages " +
            "WHERE ownerIdentityId = :ownerIdentityId AND groupId = :groupId " +
            "AND directionRaw = 'INCOMING' AND sentAt > :sinceMillis",
    )
    suspend fun unreadCount(
        ownerIdentityId: String,
        groupId: String,
        sinceMillis: Long,
    ): Int

    /** Coarse change signal for the chat list: emits whenever the messages
     *  table changes (Room re-runs the query on any insert/update/delete),
     *  so the list can recompute per-group latest message + unread and
     *  re-sort. The value itself (row count) is incidental. */
    @Query("SELECT COUNT(*) FROM messages")
    fun observeMessageChangeToken(): Flow<Int>

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

    /** Delete a single message scoped to the composite
     *  `(id, ownerIdentityId)` key (used by the failed-media Delete
     *  action). Returns the row count. */
    @Query("DELETE FROM messages WHERE id = :id AND ownerIdentityId = :ownerIdentityId")
    suspend fun deleteByIdAndOwner(id: String, ownerIdentityId: String): Int

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
