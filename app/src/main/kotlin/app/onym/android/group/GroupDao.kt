package app.onym.android.group

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Room DAO over [PersistedGroup]. The five verbs the repository
 * needs (list, select-by-id, insert, update, mark-published, delete);
 * nothing else.
 *
 * Suspend functions throughout — Room dispatches them on its
 * single-threaded query executor by default, which is fine for our
 * volume (a group is created at user-action speed).
 *
 * Mirrors the SwiftData fetch / save calls in `SwiftDataGroupStore`
 * from onym-ios PR #25 — the SwiftData side does the same
 * fetch-then-update pattern in `insertOrUpdate`, just inline.
 */
@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    suspend fun list(): List<PersistedGroup>

    /** Per-identity filter — drives `GroupRepository.snapshots`
     *  after PR-3. Argument is the [app.onym.android.identity.IdentityId.value]
     *  string. */
    @Query("SELECT * FROM groups WHERE ownerIdentityId = :ownerIdentityId ORDER BY createdAt DESC")
    suspend fun listForOwner(ownerIdentityId: String): List<PersistedGroup>

    /** Look up one identity's copy of a group. Scoped to the
     *  composite `(id, ownerIdentityId)` key so a second identity's
     *  row for the same group id is never returned in its place. */
    @Query("SELECT * FROM groups WHERE id = :id AND ownerIdentityId = :ownerIdentityId LIMIT 1")
    suspend fun findByIdAndOwner(id: String, ownerIdentityId: String): PersistedGroup?

    /**
     * Pure insert. Use [findByIdAndOwner] first to decide between
     * insert and [update] so [RoomGroupStore.insertOrUpdate] can
     * return `true` on insert / `false` on update. Throws on PK
     * conflict (the store's caller has already gone through
     * [findByIdAndOwner] and routed accordingly).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: PersistedGroup)

    @Update
    suspend fun update(record: PersistedGroup)

    /**
     * Flip [PersistedGroup.isPublishedOnChain] to true and replace
     * the [PersistedGroup.encryptedCommitment]. Caller passes a
     * non-null encrypted blob OR omits the column update entirely
     * via [markPublishedFlagOnly]. Scoped to `(id, ownerIdentityId)`
     * so it flips only the creating identity's row.
     */
    @Query(
        "UPDATE groups SET isPublishedOnChain = 1, encryptedCommitment = :encryptedCommitment " +
            "WHERE id = :id AND ownerIdentityId = :ownerIdentityId",
    )
    suspend fun markPublishedWithCommitment(id: String, ownerIdentityId: String, encryptedCommitment: ByteArray): Int

    /**
     * Flip [PersistedGroup.isPublishedOnChain] to true without
     * touching the commitment column — used when the post-anchor
     * flow doesn't have a fresh commitment to write.
     */
    @Query("UPDATE groups SET isPublishedOnChain = 1 WHERE id = :id AND ownerIdentityId = :ownerIdentityId")
    suspend fun markPublishedFlagOnly(id: String, ownerIdentityId: String): Int

    /** Stamp the chat-list last-read marker (unread badge). Scoped to
     *  `(id, ownerIdentityId)` so opening a thread as one identity doesn't
     *  clear another identity's unread state for the same group. Returns
     *  the row count. */
    @Query(
        "UPDATE groups SET lastReadAtMillis = :lastReadAtMillis " +
            "WHERE id = :id AND ownerIdentityId = :ownerIdentityId",
    )
    suspend fun markRead(id: String, ownerIdentityId: String, lastReadAtMillis: Long): Int

    /** Delete one identity's copy of a group. Scoped to the owner so
     *  deleting a chat for one identity leaves another identity's copy
     *  of the same group intact. */
    @Query("DELETE FROM groups WHERE id = :id AND ownerIdentityId = :ownerIdentityId")
    suspend fun delete(id: String, ownerIdentityId: String): Int

    /** Cascade delete for the identity-removal flow (PR-3 hooks
     *  `IdentityRepository.setRemovalListener` to call this). Returns
     *  the number of rows deleted so the caller can log the cleanup
     *  size. */
    @Query("DELETE FROM groups WHERE ownerIdentityId = :ownerIdentityId")
    suspend fun deleteForOwner(ownerIdentityId: String): Int
}
