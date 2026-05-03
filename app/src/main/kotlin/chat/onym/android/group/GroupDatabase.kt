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
    // v3 (multi-identity, PR-3): adds `ownerIdentityId` column so the
    // per-identity chats filter can `WHERE ownerIdentityId = :id`.
    // Greenfield licence per the multi-identity spec — the Application
    // builder uses `fallbackToDestructiveMigrationFrom(1, 2)` so dev
    // installs lose any local groups on next launch.
    version = 3,
    exportSchema = false,
)
abstract class GroupDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
}
