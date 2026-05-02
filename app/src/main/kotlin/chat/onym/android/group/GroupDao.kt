package chat.onym.android.group

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

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PersistedGroup?

    /**
     * Pure insert. Use [findById] first to decide between insert
     * and [update] so [RoomGroupStore.insertOrUpdate] can return
     * `true` on insert / `false` on update. Throws on PK conflict
     * (the store's caller has already gone through [findById] and
     * routed accordingly).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: PersistedGroup)

    @Update
    suspend fun update(record: PersistedGroup)

    /**
     * Flip [PersistedGroup.isPublishedOnChain] to true and replace
     * the [PersistedGroup.encryptedCommitment]. Caller passes a
     * non-null encrypted blob OR omits the column update entirely
     * via [markPublishedFlagOnly].
     */
    @Query("UPDATE groups SET isPublishedOnChain = 1, encryptedCommitment = :encryptedCommitment WHERE id = :id")
    suspend fun markPublishedWithCommitment(id: String, encryptedCommitment: ByteArray): Int

    /**
     * Flip [PersistedGroup.isPublishedOnChain] to true without
     * touching the commitment column — used when the post-anchor
     * flow doesn't have a fresh commitment to write.
     */
    @Query("UPDATE groups SET isPublishedOnChain = 1 WHERE id = :id")
    suspend fun markPublishedFlagOnly(id: String): Int

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: String): Int
}
