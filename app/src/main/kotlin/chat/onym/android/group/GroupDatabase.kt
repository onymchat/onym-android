package chat.onym.android.group

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database housing [PersistedGroup].
 *
 * Sibling to `chat.onym.android.persistence.InvitationDatabase` —
 * each persistence domain (invitations, groups, messages later) gets
 * its own `@Database` so migrations stay scoped per-domain and the
 * version number doesn't accumulate the `version = 17` rebase pain
 * the stellar-mls reference impl suffers from.
 *
 * Mirrors the SwiftData `ModelContainer([PersistedGroup.self])` that
 * `SwiftDataGroupStore` constructs in onym-ios PR #25.
 */
@Database(
    entities = [PersistedGroup::class],
    // v4 (member-profiles): adds nullable `encryptedMemberProfilesJson`
    // column. v3→v4 migration is non-destructive (the column is
    // nullable and decodes to an empty map at the store boundary), so
    // existing rows survive — see [GroupDatabaseMigrations.MIGRATION_3_4].
    version = 4,
    exportSchema = false,
)
abstract class GroupDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
}

/**
 * Hand-rolled migrations for [GroupDatabase]. Wired into the production
 * builder in [chat.onym.android.OnymApplication]; the in-memory test
 * builder skips them entirely (each test starts on the latest schema).
 */
object GroupDatabaseMigrations {
    /**
     * v3 → v4: introduce `encryptedMemberProfilesJson` (nullable BLOB).
     * Existing rows decode to an empty map; the column is filled in
     * the next time the row is rewritten via
     * [RoomGroupStore.insertOrUpdate].
     */
    val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE groups ADD COLUMN encryptedMemberProfilesJson BLOB")
        }
    }
}
