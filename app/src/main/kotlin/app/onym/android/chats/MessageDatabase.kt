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
    // v3 (composite PK): primary key becomes (id, ownerIdentityId) so
    // the same fanned-out wire message can be stored per identity —
    // fixes the second identity silently losing the message. Table
    // rebuild; existing rows migrate cleanly.
    // v4 (image attachments): adds nullable `encryptedAttachmentJson`
    // BLOB. Additive; existing rows decode to a text-only message.
    // v5 (video attachments): adds nullable `encryptedVideoAttachmentJson`
    // BLOB. Additive; existing rows decode to a message with no video.
    // v6 (albums): adds nullable `encryptedAlbumJson` BLOB. Additive;
    // existing rows decode to a message with no album.
    // v7 (voice): adds nullable `encryptedVoiceAttachmentJson` BLOB.
    // Additive; existing rows decode to a message with no voice.
    version = 7,
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

    /**
     * v2 → v3: change the primary key from `id` alone to the composite
     * `(id, ownerIdentityId)` so the same wire message fanned out to
     * two local identities keeps a row per identity. SQLite can't ALTER
     * a primary key in place, so rebuild the table and copy rows over,
     * then recreate the two indices the entity declares. Existing rows
     * migrate cleanly (their `id`s are still unique under the wider
     * key).
     */
    val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `messages_new` (" +
                    "`id` TEXT NOT NULL, " +
                    "`groupId` TEXT NOT NULL, " +
                    "`ownerIdentityId` TEXT NOT NULL, " +
                    "`sentAt` INTEGER NOT NULL, " +
                    "`directionRaw` TEXT NOT NULL, " +
                    "`statusRaw` TEXT NOT NULL, " +
                    "`groupTypeRaw` TEXT NOT NULL, " +
                    "`encryptedSenderBlsPubkeyHex` BLOB NOT NULL, " +
                    "`encryptedBody` BLOB NOT NULL, " +
                    "`replyToMessageId` TEXT, " +
                    "PRIMARY KEY(`id`, `ownerIdentityId`))",
            )
            db.execSQL(
                "INSERT INTO `messages_new` (" +
                    "`id`, `groupId`, `ownerIdentityId`, `sentAt`, `directionRaw`, " +
                    "`statusRaw`, `groupTypeRaw`, `encryptedSenderBlsPubkeyHex`, " +
                    "`encryptedBody`, `replyToMessageId`) " +
                    "SELECT `id`, `groupId`, `ownerIdentityId`, `sentAt`, `directionRaw`, " +
                    "`statusRaw`, `groupTypeRaw`, `encryptedSenderBlsPubkeyHex`, " +
                    "`encryptedBody`, `replyToMessageId` FROM `messages`",
            )
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_groupId` " +
                    "ON `messages` (`groupId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_ownerIdentityId` " +
                    "ON `messages` (`ownerIdentityId`)",
            )
        }
    }

    /**
     * v3 → v4: add the nullable `encryptedAttachmentJson` BLOB for
     * image attachments. Additive; existing rows decode to text-only.
     */
    val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN encryptedAttachmentJson BLOB")
        }
    }

    /**
     * v4 → v5: add the nullable `encryptedVideoAttachmentJson` BLOB for
     * video attachments. Additive; existing rows decode to no video.
     */
    val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN encryptedVideoAttachmentJson BLOB")
        }
    }

    /**
     * v5 → v6: add the nullable `encryptedAlbumJson` BLOB for multi-media
     * albums. Additive; existing rows decode to no album.
     */
    val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN encryptedAlbumJson BLOB")
        }
    }

    /**
     * v6 → v7: add the nullable `encryptedVoiceAttachmentJson` BLOB for
     * voice messages. Additive; existing rows decode to no voice.
     */
    val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN encryptedVoiceAttachmentJson BLOB")
        }
    }
}
