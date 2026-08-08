package app.onym.android.group

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database housing [PersistedGroup].
 *
 * Sibling to `app.onym.android.persistence.InvitationDatabase` —
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
    // v5 (admin Ed25519): adds nullable `encryptedAdminEd25519PubkeyHex`
    // column on top of v4's `encryptedMemberProfilesJson`. Both
    // migrations are non-destructive — existing rows decode to null
    // at the store boundary.
    // v6 (group avatar): adds nullable `encryptedAvatar` column —
    // iOS #164–#166 parity. Existing rows decode to a null avatar.
    // v7 (composite PK): primary key becomes (id, ownerIdentityId) so
    // two local identities can each hold their own row for the same
    // on-chain group — fixes "last invited identity wins". Table
    // rebuild; existing rows migrate cleanly (their ids stay unique
    // under the composite key).
    // v8 (last-read): adds nullable `lastReadAtMillis` INTEGER for the
    // chat-list unread badge. Additive; existing rows decode to null
    // (never opened → everything unread until first open).
    version = 8,
    exportSchema = false,
)
abstract class GroupDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
}

/**
 * Hand-rolled migrations for [GroupDatabase]. Wired into the production
 * builder in [app.onym.android.OnymApplication]; the in-memory test
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

    /**
     * v4 → v5: introduce `encryptedAdminEd25519PubkeyHex` (nullable
     * BLOB). Existing rows decode to null (best-effort fallback for
     * pre-PR-84 announcements).
     */
    val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE groups ADD COLUMN encryptedAdminEd25519PubkeyHex BLOB")
        }
    }

    /**
     * v5 → v6: introduce `encryptedAvatar` (nullable BLOB) for the
     * group photo. Existing rows decode to a null avatar (no photo).
     */
    val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE groups ADD COLUMN encryptedAvatar BLOB")
        }
    }

    /**
     * v6 → v7: change the primary key from `id` alone to the composite
     * `(id, ownerIdentityId)` so two local identities can each hold
     * their own row for the same on-chain group. SQLite can't ALTER a
     * primary key in place, so rebuild the table and copy rows over —
     * existing rows keep their data (their `id`s are still unique under
     * the wider key). Also (re)creates the `ownerIdentityId` index the
     * new entity declares.
     */
    val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups_new` (" +
                    "`id` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`epoch` INTEGER NOT NULL, " +
                    "`tierRaw` INTEGER NOT NULL, " +
                    "`groupTypeRaw` TEXT NOT NULL, " +
                    "`isPublishedOnChain` INTEGER NOT NULL, " +
                    "`ownerIdentityId` TEXT NOT NULL, " +
                    "`encryptedName` BLOB NOT NULL, " +
                    "`encryptedGroupSecret` BLOB NOT NULL, " +
                    "`encryptedMembersJson` BLOB NOT NULL, " +
                    "`encryptedSalt` BLOB NOT NULL, " +
                    "`encryptedCommitment` BLOB, " +
                    "`encryptedAdminPubkeyHex` BLOB, " +
                    "`encryptedMemberProfilesJson` BLOB, " +
                    "`encryptedAdminEd25519PubkeyHex` BLOB, " +
                    "`encryptedAvatar` BLOB, " +
                    "PRIMARY KEY(`id`, `ownerIdentityId`))",
            )
            db.execSQL(
                "INSERT INTO `groups_new` (" +
                    "`id`, `createdAt`, `epoch`, `tierRaw`, `groupTypeRaw`, " +
                    "`isPublishedOnChain`, `ownerIdentityId`, `encryptedName`, " +
                    "`encryptedGroupSecret`, `encryptedMembersJson`, `encryptedSalt`, " +
                    "`encryptedCommitment`, `encryptedAdminPubkeyHex`, " +
                    "`encryptedMemberProfilesJson`, `encryptedAdminEd25519PubkeyHex`, " +
                    "`encryptedAvatar`) " +
                    "SELECT `id`, `createdAt`, `epoch`, `tierRaw`, `groupTypeRaw`, " +
                    "`isPublishedOnChain`, `ownerIdentityId`, `encryptedName`, " +
                    "`encryptedGroupSecret`, `encryptedMembersJson`, `encryptedSalt`, " +
                    "`encryptedCommitment`, `encryptedAdminPubkeyHex`, " +
                    "`encryptedMemberProfilesJson`, `encryptedAdminEd25519PubkeyHex`, " +
                    "`encryptedAvatar` FROM `groups`",
            )
            db.execSQL("DROP TABLE `groups`")
            db.execSQL("ALTER TABLE `groups_new` RENAME TO `groups`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_groups_ownerIdentityId` " +
                    "ON `groups` (`ownerIdentityId`)",
            )
        }
    }

    /**
     * v7 → v8: add the nullable `lastReadAtMillis` INTEGER for the
     * chat-list unread badge. Additive; existing rows decode to null.
     */
    val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE groups ADD COLUMN lastReadAtMillis INTEGER")
        }
    }
}
