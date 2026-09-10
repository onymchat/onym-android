package app.onym.android.group

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO over [PersistedPendingAnchor]. */
@Dao
interface PendingAnchorDao {

    /**
     * Newest attempt first. The most recent one is the likeliest to be
     * the one whose answer went missing, and [adoptLandedAnchor] stops
     * at the first candidate whose commitment matches — so the order
     * decides how much recomputation the common case costs, not which
     * answer comes back.
     */
    @Query(
        "SELECT * FROM pending_anchors " +
            "WHERE groupIdHex = :groupIdHex AND ownerIdentityId = :ownerIdentityId " +
            "ORDER BY createdAtMillis DESC",
    )
    suspend fun pending(groupIdHex: String, ownerIdentityId: String): List<PersistedPendingAnchor>

    /** `ABORT` is unreachable — the key is generated — but stated
     *  rather than left to `REPLACE`, which would silently drop a
     *  candidate salt if the key ever stopped being generated. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PersistedPendingAnchor)

    /**
     * Sweep every attempt that proved from [throughEpoch] or earlier.
     *
     * Comparison is on the raw stored bits, which orders correctly for
     * every epoch a group can actually reach: the value is a counter
     * incremented once per member-add, so the sign bit is not in play.
     */
    @Query(
        "DELETE FROM pending_anchors " +
            "WHERE groupIdHex = :groupIdHex AND ownerIdentityId = :ownerIdentityId " +
            "AND epochOld <= :throughEpoch",
    )
    suspend fun clearThrough(groupIdHex: String, ownerIdentityId: String, throughEpoch: Long)
}
