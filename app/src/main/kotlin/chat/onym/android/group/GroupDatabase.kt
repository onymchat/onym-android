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
    version = 1,
    // Schema export is for cross-version diffing during migration
    // authoring; nothing to diff at v1 and KSP warns loudly about
    // the missing `room.schemaLocation` directory otherwise. Flip
    // to true and add the apt arg when version 2 lands.
    exportSchema = false,
)
abstract class GroupDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
}
