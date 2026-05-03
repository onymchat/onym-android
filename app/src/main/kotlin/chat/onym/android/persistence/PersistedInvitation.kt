package chat.onym.android.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row shape for an incoming Nostr invitation.
 *
 * `id` is the Nostr event id (lowercase hex). The Nostr inbox
 * transport already exposes it on the relay, so storing it
 * cleartext doesn't leak anything — and it's the natural primary
 * key for dedup.
 *
 * `receivedAt` is wall-clock ms since the Unix epoch. Stored as a
 * primitive INTEGER so the DAO can `ORDER BY receivedAt DESC`
 * without a converter.
 *
 * `statusRaw` is [IncomingInvitationStatus.name]. Keeping it a
 * primitive TEXT lets the DAO update it with a one-line `@Query`,
 * no `@Update` reflection-roundtrip required.
 *
 * `encryptedPayload` is the AES-GCM-wrapped output of
 * [StorageEncryption.encrypt] over the inbound NaCl-sealed bytes.
 * Stored as BLOB; opaque to SQLite.
 *
 * `ownerIdentityIdString` is the
 * [chat.onym.android.identity.IdentityId.value] of the identity
 * this envelope was addressed to (the identity whose inbox tag
 * the fan-out subscription delivered it on). Stored plaintext +
 * indexed so the per-identity filter happens in SQL, not
 * in-process. The owner ID is a random per-device UUID — nothing
 * to leak. Added in PR-6 of the deeplink-invite stack (mirrors
 * onym-ios PR #59).
 */
@Entity(
    tableName = "incoming_invitations",
    indices = [Index(value = ["ownerIdentityIdString"])],
)
data class PersistedInvitation(
    @PrimaryKey val id: String,
    val encryptedPayload: ByteArray,
    val receivedAt: Long,
    val statusRaw: String,
    @ColumnInfo(defaultValue = "") val ownerIdentityIdString: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedInvitation) return false
        return id == other.id &&
            encryptedPayload.contentEquals(other.encryptedPayload) &&
            receivedAt == other.receivedAt &&
            statusRaw == other.statusRaw &&
            ownerIdentityIdString == other.ownerIdentityIdString
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + statusRaw.hashCode()
        result = 31 * result + ownerIdentityIdString.hashCode()
        return result
    }
}
