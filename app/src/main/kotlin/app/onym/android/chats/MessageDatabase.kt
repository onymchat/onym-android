package app.onym.android.chats

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database housing [PersistedMessage]. **Separate database
 * (and separate `.db` file) from
 * [app.onym.android.group.GroupDatabase]** so a schema bump in
 * either domain doesn't force a wipe of the other — same rationale
 * the `GroupDatabase` doc-comment calls out:
 *
 * > each persistence domain (invitations, groups, messages later)
 * > gets its own `@Database` so migrations stay scoped per-domain
 * > and the version number doesn't accumulate the `version = 17`
 * > rebase pain the stellar-mls reference impl suffers from.
 *
 * Production wiring (when it lands in a later PR): use database
 * name `"app.onym.android.messages"`.
 *
 * Mirrors the SwiftData `ModelContainer([PersistedMessage.self])`
 * that `SwiftDataMessageStore` constructs in onym-ios PR #148.
 */
@Database(
    entities = [PersistedMessage::class],
    // v2 (reply-to): adds the nullable `replyToMessageId` TEXT column
    // — onym-ios #173 parity. Additive + non-destructive; existing
    // rows decode to a null reply target (a non-reply message).
    version = 2,
    exportSchema = false,
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}

/**
 * Hand-rolled migrations for [MessageDatabase]. Wired into the
 * production builder in [app.onym.android.OnymApplication]; the
 * in-memory test builder skips them (each test starts on the latest
 * schema). Sibling to
 * [app.onym.android.group.GroupDatabaseMigrations].
 */
object MessageDatabaseMigrations {
    /**
     * v1 → v2: introduce `replyToMessageId` (nullable TEXT) holding
     * the UUID string of the replied-to message. Existing rows decode
     * to a null reply target; the column fills in only on rows
     * written by a reply-aware sender. Mirrors the SwiftData
     * lightweight migration in onym-ios PR #173.
     */
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT")
        }
    }
}
