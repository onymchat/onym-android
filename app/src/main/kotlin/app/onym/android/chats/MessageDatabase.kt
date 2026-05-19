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
    version = 1,
    exportSchema = false,
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
