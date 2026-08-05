package app.onym.android.group

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Direct tests for [GroupDatabaseMigrations.MIGRATION_8_9] against
 * hand-built v8 schemas. `exportSchema = false` rules out Room's
 * `MigrationTestHelper`, so the migration's SQL runs on a raw
 * framework database instead — which is also exactly what we need to
 * model the TWO v8 shapes in the wild:
 *
 *  - a device that migrated 7→8 (NO `encryptedInvitationMessage`
 *    column — the entity gained it without a version bump), and
 *  - a fresh install created at "v8" after that change (column
 *    present).
 *
 * The migration must add `membershipRevoked` to both and
 * `encryptedInvitationMessage` only where missing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GroupDatabaseMigrationTest {

    @Test
    fun migrate8to9_addsBothColumns_onDeviceMigratedFrom7() {
        withV8Database(hasInvitationMessageColumn = false) { db ->
            GroupDatabaseMigrations.MIGRATION_8_9.migrate(db)

            val columns = columnNames(db)
            assertTrue("membershipRevoked missing", "membershipRevoked" in columns)
            assertTrue(
                "encryptedInvitationMessage missing",
                "encryptedInvitationMessage" in columns,
            )
            // Existing rows default to active membership.
            db.query("SELECT membershipRevoked FROM groups").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate8to9_skipsDuplicateInvitationColumn_onFreshV8Install() {
        withV8Database(hasInvitationMessageColumn = true) { db ->
            // Must not throw "duplicate column name".
            GroupDatabaseMigrations.MIGRATION_8_9.migrate(db)

            val columns = columnNames(db)
            assertTrue("membershipRevoked" in columns)
            assertEquals(1, columns.count { it == "encryptedInvitationMessage" })
        }
    }

    @Test
    fun migrate8to9_preservesExistingRowData() {
        withV8Database(hasInvitationMessageColumn = false) { db ->
            GroupDatabaseMigrations.MIGRATION_8_9.migrate(db)
            db.query("SELECT id, epoch, ownerIdentityId FROM groups").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("aa".repeat(32), cursor.getString(0))
                assertEquals(3L, cursor.getLong(1))
                assertEquals("owner", cursor.getString(2))
            }
        }
    }

    // ─── end-to-end: Room actually OPENS the migrated database ─────

    @Test
    fun room_opensV8DatabaseAtV9_deviceMigratedFrom7Shape() {
        assertRoomOpensMigratedV8(hasInvitationMessageColumn = false)
    }

    @Test
    fun room_opensV8DatabaseAtV9_freshV8InstallShape() {
        assertRoomOpensMigratedV8(hasInvitationMessageColumn = true)
    }

    /**
     * The raw-SQL tests above prove the migration's DDL; this proves
     * what actually matters — that Room OPENS the migrated database.
     * After running migrations Room validates the resulting schema
     * against the entity-derived expectation (`TableInfo` comparison)
     * and throws "Migration didn't properly handle: groups" on any
     * mismatch — including the `DEFAULT 0` on `membershipRevoked`,
     * the first DB-side column default in this schema's history.
     * No destructive fallback is registered here, so a validation
     * failure fails the test instead of silently wiping.
     */
    private fun assertRoomOpensMigratedV8(hasInvitationMessageColumn: Boolean) {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val dbName = "migration-e2e-${System.nanoTime()}.db"
        val dbFile = ctx.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Build the v8 file with the framework (non-Room) SQLite stack,
        // exactly like a real pre-upgrade install left it.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.execSQL(v8CreateTableSql(hasInvitationMessageColumn))
            // Real v8 installs carry this index (created by the 6→7
            // rebuild / fresh create). Room's post-migration validation
            // checks indices too, so the fixture must be faithful.
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_groups_ownerIdentityId` " +
                    "ON `groups` (`ownerIdentityId`)",
            )
            raw.execSQL(v8SeedRowSql())
            raw.version = 8
        }

        val db = Room.databaseBuilder(ctx, GroupDatabase::class.java, dbName)
            .addMigrations(
                GroupDatabaseMigrations.MIGRATION_3_4,
                GroupDatabaseMigrations.MIGRATION_4_5,
                GroupDatabaseMigrations.MIGRATION_5_6,
                GroupDatabaseMigrations.MIGRATION_6_7,
                GroupDatabaseMigrations.MIGRATION_7_8,
                GroupDatabaseMigrations.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()
        try {
            // First DAO touch forces open → migrate → validate.
            val rows = runBlocking { db.groupDao().list() }
            assertEquals(1, rows.size)
            assertEquals("aa".repeat(32), rows.single().id)
            assertFalse(rows.single().membershipRevoked)
        } finally {
            db.close()
            dbFile.delete()
        }
    }

    // ─── helpers ───────────────────────────────────────────────────

    /** Open a raw SQLite db, create the v8 `groups` table (post-7→8
     *  shape, optionally with the drifted invitation column), seed
     *  one row, and hand it to [block]. */
    private fun withV8Database(
        hasInvitationMessageColumn: Boolean,
        block: (SupportSQLiteDatabase) -> Unit,
    ) {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {}
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(v8CreateTableSql(hasInvitationMessageColumn))
            db.execSQL(v8SeedRowSql())
            block(db)
        }
    }

    private fun v8CreateTableSql(hasInvitationMessageColumn: Boolean): String {
        val invitationColumn = if (hasInvitationMessageColumn) {
            "`encryptedInvitationMessage` BLOB, "
        } else {
            ""
        }
        return "CREATE TABLE `groups` (" +
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
            invitationColumn +
            "`lastReadAtMillis` INTEGER, " +
            "PRIMARY KEY(`id`, `ownerIdentityId`))"
    }

    private fun v8SeedRowSql(): String =
        "INSERT INTO `groups` (" +
            "id, createdAt, epoch, tierRaw, groupTypeRaw, isPublishedOnChain, " +
            "ownerIdentityId, encryptedName, encryptedGroupSecret, " +
            "encryptedMembersJson, encryptedSalt) " +
            "VALUES ('${"aa".repeat(32)}', 0, 3, 1, 'tyranny', 1, 'owner', " +
            "x'00', x'00', x'00', x'00')"

    private fun columnNames(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA table_info(`groups`)").use { cursor ->
            val names = mutableListOf<String>()
            val idx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names.add(cursor.getString(idx))
            names
        }
}
