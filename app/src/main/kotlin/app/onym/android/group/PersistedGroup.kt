package app.onym.android.group

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row shape for one chat group on disk. Splits the schema into
 * plain (queryable, non-identifying) and AES-GCM-encrypted (sensitive)
 * columns — same pattern as `PersistedInvitation` (PR #16).
 *
 * Plain columns:
 *  - [id] — 64-char hex of the 32-byte group ID. Already public on
 *    chain (the contract stores it as the entry key), so encrypting
 *    locally would buy nothing while breaking dedup lookups.
 *  - [createdAt] — wall-clock ms since the Unix epoch. Stored
 *    primitive INTEGER so the DAO can `ORDER BY createdAt DESC`
 *    without a converter.
 *  - [epoch], [tierRaw], [groupTypeRaw], [isPublishedOnChain] — small
 *    enums / counts; not user-identifying.
 *
 * Encrypted columns (`StorageEncryption.encrypt`):
 *  - [encryptedName] — user-supplied; can leak intent.
 *  - [encryptedGroupSecret] — drives all message-key derivation.
 *  - [encryptedMembersJson] — the lex-sorted roster (BLS pubkeys +
 *    leaf hashes) encoded as JSON before encryption.
 *  - [encryptedSalt], [encryptedCommitment], [encryptedAdminPubkeyHex].
 *
 * Decryption boundary lives in [RoomGroupStore], not here —
 * [PersistedGroup] is intentionally dumb storage so Room never has
 * to reason about `app.onym.android.persistence.StorageEncryption`.
 *
 * `epoch` is stored as `Long` (not `ULong`) because Room can't
 * persist unsigned types directly — the domain layer also keeps it
 * as `Long` to match (the relayer JSON serializer is what cares
 * about unsigned semantics, and that's covered elsewhere).
 *
 * Mirrors `PersistedGroup.swift` from onym-ios PR #25.
 */
@Entity(tableName = "groups")
data class PersistedGroup(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val epoch: Long,
    val tierRaw: Int,
    val groupTypeRaw: String,
    val isPublishedOnChain: Boolean,
    /** [app.onym.android.identity.IdentityId.value]. Indexed (PR-3
     *  added the column) so the per-identity flow filter (`SELECT *
     *  FROM groups WHERE ownerIdentityId = :id`) doesn't full-scan.
     *  Schema bumped to 3 — `fallbackToDestructiveMigration` cleans
     *  any stale rows from the previous shape (no production data;
     *  greenfield licence per the multi-identity spec). */
    val ownerIdentityId: String,

    val encryptedName: ByteArray,
    val encryptedGroupSecret: ByteArray,
    val encryptedMembersJson: ByteArray,
    val encryptedSalt: ByteArray,
    val encryptedCommitment: ByteArray? = null,
    val encryptedAdminPubkeyHex: ByteArray? = null,
    /**
     * Optional so Room's auto-migration can land an extra column on
     * existing rows without a wipe. `null` decodes to an empty map at
     * the [RoomGroupStore] boundary. Stores the encrypted JSON of
     * `Map<String, MemberProfile>` keyed by lowercase BLS pubkey hex.
     */
    val encryptedMemberProfilesJson: ByteArray? = null,
    /**
     * Optional Ed25519 pubkey of the admin (PR 84). `null` on rows
     * migrated from the pre-PR-84 schema, or for governance models
     * without an admin (Anarchy / OneOnOne).
     */
    val encryptedAdminEd25519PubkeyHex: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedGroup) return false
        return id == other.id &&
            createdAt == other.createdAt &&
            epoch == other.epoch &&
            tierRaw == other.tierRaw &&
            groupTypeRaw == other.groupTypeRaw &&
            isPublishedOnChain == other.isPublishedOnChain &&
            ownerIdentityId == other.ownerIdentityId &&
            encryptedName.contentEquals(other.encryptedName) &&
            encryptedGroupSecret.contentEquals(other.encryptedGroupSecret) &&
            encryptedMembersJson.contentEquals(other.encryptedMembersJson) &&
            encryptedSalt.contentEquals(other.encryptedSalt) &&
            (encryptedCommitment?.contentEquals(other.encryptedCommitment) ?: (other.encryptedCommitment == null)) &&
            (encryptedAdminPubkeyHex?.contentEquals(other.encryptedAdminPubkeyHex) ?: (other.encryptedAdminPubkeyHex == null)) &&
            (encryptedMemberProfilesJson?.contentEquals(other.encryptedMemberProfilesJson) ?: (other.encryptedMemberProfilesJson == null)) &&
            (encryptedAdminEd25519PubkeyHex?.contentEquals(other.encryptedAdminEd25519PubkeyHex) ?: (other.encryptedAdminEd25519PubkeyHex == null))
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + createdAt.hashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + tierRaw
        h = 31 * h + groupTypeRaw.hashCode()
        h = 31 * h + isPublishedOnChain.hashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + encryptedName.contentHashCode()
        h = 31 * h + encryptedGroupSecret.contentHashCode()
        h = 31 * h + encryptedMembersJson.contentHashCode()
        h = 31 * h + encryptedSalt.contentHashCode()
        h = 31 * h + (encryptedCommitment?.contentHashCode() ?: 0)
        h = 31 * h + (encryptedAdminPubkeyHex?.contentHashCode() ?: 0)
        h = 31 * h + (encryptedMemberProfilesJson?.contentHashCode() ?: 0)
        h = 31 * h + (encryptedAdminEd25519PubkeyHex?.contentHashCode() ?: 0)
        return h
    }
}
