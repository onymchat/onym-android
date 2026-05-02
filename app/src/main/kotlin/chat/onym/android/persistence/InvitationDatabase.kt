package chat.onym.android.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database housing [PersistedInvitation].
 *
 * Currently a single-table DB. Future persistence domains (groups,
 * messages, contact aliases — see the planned-box notes in
 * `README.md`'s architecture section) can either land here or in
 * sibling `@Database` classes. Sibling classes keep migrations
 * scoped per-domain and avoid the `version = 17` rebase pain the
 * stellar-mls reference impl shows.
 */
@Database(
    entities = [PersistedInvitation::class],
    version = 1,
    // Schema export is for cross-version diffing during migration
    // authoring; nothing to diff at v1 and KSP warns loudly about
    // the missing `room.schemaLocation` directory otherwise.
    // Flip to true and add the apt arg when version 2 lands.
    exportSchema = false,
)
abstract class InvitationDatabase : RoomDatabase() {
    abstract fun invitationDao(): InvitationDao
}
