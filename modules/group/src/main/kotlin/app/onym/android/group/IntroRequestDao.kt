package app.onym.android.group

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO over [PersistedIntroRequest]. */
@Dao
interface IntroRequestDao {

    /** Newest-first, matching `InMemoryIntroRequestStore`'s ordering. */
    @Query("SELECT * FROM intro_requests ORDER BY receivedAtMillis DESC")
    suspend fun list(): List<PersistedIntroRequest>

    @Query("SELECT COUNT(*) FROM intro_requests WHERE id = :id")
    suspend fun count(id: String): Int

    /** `ABORT` rather than `REPLACE`: the caller checks for an existing
     *  row first and this is the backstop, so a silent overwrite would
     *  reset a row's `firstSeenAtMillis` and with it its retention
     *  clock — a replayed event could then keep a request alive forever. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PersistedIntroRequest)

    /** Backfill for rows written before `firstSeenAtMillis` existed. */
    @Query("UPDATE intro_requests SET firstSeenAtMillis = :atMillis WHERE id = :id")
    suspend fun stampFirstSeen(id: String, atMillis: Long)

    @Query("DELETE FROM intro_requests WHERE id = :id")
    suspend fun delete(id: String)

    /** Retention sweep. Rows with a null `firstSeenAtMillis` are left
     *  alone — they are backfilled on next sight rather than swept on
     *  the migration read. */
    @Query(
        "DELETE FROM intro_requests " +
            "WHERE firstSeenAtMillis IS NOT NULL AND firstSeenAtMillis < :cutoffMillis",
    )
    suspend fun deleteOlderThan(cutoffMillis: Long): Int
}
