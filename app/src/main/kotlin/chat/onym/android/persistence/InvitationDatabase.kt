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
    // v2: PR-6 of the deeplink-invite stack adds the
    // `ownerIdentityIdString` column for the per-identity
    // decryption-routing filter. Pre-1.0 + greenfield licence — no
    // Migration class. Builders MUST set
    // `.fallbackToDestructiveMigration()` so a v1→v2 jump on an
    // existing install drops the table (re-fetched from relays on
    // next launch) instead of crashing on schema mismatch.
    version = 2,
    // Schema export is for cross-version diffing during migration
    // authoring; we don't write Migration classes (destructive
    // fallback per the greenfield licence), so KSP-warn-suppression
    // by leaving this off is fine.
    exportSchema = false,
)
abstract class InvitationDatabase : RoomDatabase() {
    abstract fun invitationDao(): InvitationDao
}
