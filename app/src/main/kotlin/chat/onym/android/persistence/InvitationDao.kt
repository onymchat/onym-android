package chat.onym.android.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO over [PersistedInvitation]. Three CRUD verbs the
 * repository needs (list, insert with dedup, update status, delete);
 * nothing else.
 *
 * Suspend functions throughout — Room dispatches them on its
 * single-threaded query executor by default, which is fine for our
 * volume (an invitation arrives at user-action speed).
 */
@Dao
interface InvitationDao {

    @Query("SELECT * FROM incoming_invitations ORDER BY receivedAt DESC")
    suspend fun list(): List<PersistedInvitation>

    /**
     * Insert with `IGNORE` on PK conflict. Returns the new row id on
     * insert, or `-1` if the row was ignored (id collision).
     * Callers use the `-1` sentinel for dedup.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: PersistedInvitation): Long

    @Query("UPDATE incoming_invitations SET statusRaw = :statusRaw WHERE id = :id")
    suspend fun updateStatus(id: String, statusRaw: String): Int

    @Query("DELETE FROM incoming_invitations WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Cascade-delete every row owned by [ownerIdentityIdString].
     *  Returns the number of rows removed. Used by the
     *  identity-removed listener (see
     *  [chat.onym.android.inbox.IncomingInvitationsRepository]). */
    @Query("DELETE FROM incoming_invitations WHERE ownerIdentityIdString = :ownerIdentityIdString")
    suspend fun deleteForOwner(ownerIdentityIdString: String): Int
}
