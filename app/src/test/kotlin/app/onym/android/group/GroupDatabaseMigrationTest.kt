package app.onym.android.group

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
            val invitationColumn = if (hasInvitationMessageColumn) {
                "`encryptedInvitationMessage` BLOB, "
            } else {
                ""
            }
            db.execSQL(
                "CREATE TABLE `groups` (" +
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
                    "PRIMARY KEY(`id`, `ownerIdentityId`))",
            )
            db.execSQL(
                "INSERT INTO `groups` (" +
                    "id, createdAt, epoch, tierRaw, groupTypeRaw, isPublishedOnChain, " +
                    "ownerIdentityId, encryptedName, encryptedGroupSecret, " +
                    "encryptedMembersJson, encryptedSalt) " +
                    "VALUES ('${"aa".repeat(32)}', 0, 3, 1, 'tyranny', 1, 'owner', " +
                    "x'00', x'00', x'00', x'00')",
            )
            block(db)
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA table_info(`groups`)").use { cursor ->
            val names = mutableListOf<String>()
            val idx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names.add(cursor.getString(idx))
            names
        }
}
