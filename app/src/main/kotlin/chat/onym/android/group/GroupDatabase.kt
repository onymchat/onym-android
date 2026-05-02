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
    // v2 (PR-C follow-up, mirrors onym-ios PR #27): groupTypeRaw
    // column flipped Int → String to match the relayer + contract
    // wire spelling. The OnymApplication builder uses
    // `fallbackToDestructiveMigrationFrom(1)` since PR-C only just
    // shipped and there's no production data to preserve — pre-PR-C
    // dev installs lose any local groups on next app launch.
    version = 2,
    // Schema export is for cross-version diffing during migration
    // authoring; nothing to diff at v2 with destructive migration.
    exportSchema = false,
)
abstract class GroupDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
}
