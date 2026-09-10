package app.onym.android.group

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one in-flight anchor attempt. Same plain-vs-encrypted
 * split as [PersistedGroup] / [PersistedIntroRequest]: anything queried
 * on stays plain, the sensitive bytes ride through `StorageEncryption`.
 *
 * Plain:
 *  - [groupIdHex] / [ownerIdentityId] — the lookup key.
 *  - [epochOld] — decides which rows can still be waiting to land and
 *    which are swept.
 *  - [createdAtMillis] — newest-first ordering, so the most recent
 *    attempt is checked against the chain first.
 *
 * Encrypted:
 *  - [encryptedSaltNew] — the blinding factor for a state that may
 *    already be on a public chain. In the clear, the database file
 *    would let anyone confirm a guessed roster against that
 *    commitment — which is the one thing the salt exists to prevent.
 *  - [encryptedJoinerPublicKey] / [encryptedJoinerLeafHash] — who was
 *    being added, and to whom the leaf belongs.
 *
 * The primary key is a generated id rather than
 * `(group, owner, joiner, epoch)`: each retry of the same join draws a
 * *new* salt, and every one of them is a candidate for having landed.
 * Collapsing them would throw away all but the last, which is
 * precisely the one that didn't.
 */
@Entity(tableName = "pending_anchors")
data class PersistedPendingAnchor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupIdHex: String,
    val ownerIdentityId: String,
    /** Room has no ULong column type; stored as the raw bits and read
     *  back with `toULong()`, which round-trips every value. */
    val epochOld: Long,
    val createdAtMillis: Long,
    val encryptedJoinerPublicKey: ByteArray,
    val encryptedJoinerLeafHash: ByteArray,
    val encryptedSaltNew: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedPendingAnchor) return false
        return id == other.id &&
            groupIdHex == other.groupIdHex &&
            ownerIdentityId == other.ownerIdentityId &&
            epochOld == other.epochOld &&
            createdAtMillis == other.createdAtMillis &&
            encryptedJoinerPublicKey.contentEquals(other.encryptedJoinerPublicKey) &&
            encryptedJoinerLeafHash.contentEquals(other.encryptedJoinerLeafHash) &&
            encryptedSaltNew.contentEquals(other.encryptedSaltNew)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + groupIdHex.hashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + epochOld.hashCode()
        h = 31 * h + createdAtMillis.hashCode()
        h = 31 * h + encryptedJoinerPublicKey.contentHashCode()
        h = 31 * h + encryptedJoinerLeafHash.contentHashCode()
        h = 31 * h + encryptedSaltNew.contentHashCode()
        return h
    }
}
