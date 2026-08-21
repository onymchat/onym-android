package app.onym.android.inbox

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Room row for one pending chat. Same plain-vs-encrypted split as
 * [app.onym.android.group.PersistedGroup] / `PersistedIntroRequest`:
 * what we filter or sort on stays plain, everything a person wrote or is
 * addressed by rides through `StorageEncryption`.
 *
 * Plain:
 *  - [id] — `<group id hex>:<owner>`. The group id is already public
 *    on-chain and the owner id is a per-device value, so encrypting the
 *    key would buy nothing and break dedup lookups.
 *  - [ownerIdentityId] — the filter for the identity-removal cascade.
 *  - [groupIdHex] — the *only* copy of the group id, and the column the
 *    verification overlay and the materialized sweep compare against
 *    without decrypting anything.
 *  - [receivedAtMillis] — the sort column.
 *  - [statusRaw] / [failureRaw] — which waiting state this is, as codes
 *    rather than sentences, so re-wording or re-translating the copy is
 *    not a migration.
 *
 * Encrypted:
 *  - [encryptedIntroPublicKey] — correlates this device to a specific
 *    invite link.
 *  - [encryptedGroupName], [encryptedInviterAlias],
 *    [encryptedInvitationMessage] — all user-supplied, all leak intent.
 */
@Entity(tableName = "pending_chats")
data class PersistedPendingChat(
    @PrimaryKey val id: String,
    val ownerIdentityId: String,
    val groupIdHex: String,
    val receivedAtMillis: Long,
    /** Authenticated sender time for offer freshness; null for link joins. */
    val offerReceivedAtMillis: Long?,
    val statusRaw: String,
    /** Only set for a failed send — a [PendingChat.SendFailure] raw value. */
    val failureRaw: String?,
    val encryptedIntroPublicKey: ByteArray,
    /** Null when the invite carried no group name — distinct from an
     *  empty name, which the row keeps as encrypted emptiness. */
    val encryptedGroupName: ByteArray?,
    val encryptedInviterAlias: ByteArray,
    val encryptedInvitationMessage: ByteArray?,
    /** The name typed on the confirmation screen. Encrypted with the
     *  rest of the person-supplied text; null means nothing has been
     *  sent for this row yet. */
    val encryptedJoinerLabel: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedPendingChat) return false
        return id == other.id &&
            ownerIdentityId == other.ownerIdentityId &&
            groupIdHex == other.groupIdHex &&
            receivedAtMillis == other.receivedAtMillis &&
            offerReceivedAtMillis == other.offerReceivedAtMillis &&
            statusRaw == other.statusRaw &&
            failureRaw == other.failureRaw &&
            encryptedIntroPublicKey.contentEquals(other.encryptedIntroPublicKey) &&
            (encryptedGroupName ?: ByteArray(0)).contentEquals(
                other.encryptedGroupName ?: ByteArray(0),
            ) &&
            encryptedInviterAlias.contentEquals(other.encryptedInviterAlias) &&
            (encryptedInvitationMessage ?: ByteArray(0)).contentEquals(
                other.encryptedInvitationMessage ?: ByteArray(0),
            ) &&
            (encryptedJoinerLabel ?: ByteArray(0)).contentEquals(
                other.encryptedJoinerLabel ?: ByteArray(0),
            )
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + groupIdHex.hashCode()
        h = 31 * h + receivedAtMillis.hashCode()
        h = 31 * h + (offerReceivedAtMillis?.hashCode() ?: 0)
        h = 31 * h + statusRaw.hashCode()
        h = 31 * h + (failureRaw?.hashCode() ?: 0)
        h = 31 * h + encryptedIntroPublicKey.contentHashCode()
        h = 31 * h + (encryptedGroupName?.contentHashCode() ?: 0)
        h = 31 * h + encryptedInviterAlias.contentHashCode()
        h = 31 * h + (encryptedInvitationMessage?.contentHashCode() ?: 0)
        h = 31 * h + (encryptedJoinerLabel?.contentHashCode() ?: 0)
        return h
    }
}

/** Room DAO over [PersistedPendingChat]. */
@Dao
interface PendingChatDao {
    /** Newest-first, matching [InMemoryPendingChatStore]'s ordering. */
    @Query("SELECT * FROM pending_chats ORDER BY receivedAtMillis DESC")
    suspend fun list(): List<PersistedPendingChat>

    @Query("SELECT COUNT(*) FROM pending_chats WHERE id = :id")
    suspend fun count(id: String): Int

    /** `ABORT` rather than `REPLACE`: the caller checks for an existing
     *  row first and this is the backstop. A silent overwrite would
     *  knock a row that has already asked back to an unanswered offer,
     *  every time a relay replayed the invitation. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PersistedPendingChat)

    @Query("UPDATE pending_chats SET statusRaw = :statusRaw, failureRaw = :failureRaw WHERE id = :id")
    suspend fun setStatus(id: String, statusRaw: String, failureRaw: String?)

    /** The offer-time clause is the whole point: it makes
     *  "newer offer wins" a property of the write rather than of a
     *  read the caller did first, so a replayed older offer racing a
     *  re-invite cannot restore a revoked intro key. See
     *  [PendingChatStore.refreshOffer]. */
    @Query(
        "UPDATE pending_chats SET encryptedIntroPublicKey = :introPublicKey, " +
            "encryptedGroupName = :groupName, encryptedInviterAlias = :inviterAlias, " +
            "encryptedInvitationMessage = :invitationMessage, " +
            "receivedAtMillis = :receivedAtMillis, offerReceivedAtMillis = :receivedAtMillis " +
            "WHERE id = :id AND " +
            "(offerReceivedAtMillis IS NULL OR offerReceivedAtMillis < :receivedAtMillis)",
    )
    suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: ByteArray?,
        inviterAlias: ByteArray,
        invitationMessage: ByteArray?,
        receivedAtMillis: Long,
    )

    @Query("UPDATE pending_chats SET encryptedJoinerLabel = :label WHERE id = :id")
    suspend fun setJoinerLabel(id: String, label: ByteArray)

    @Query("UPDATE pending_chats SET encryptedIntroPublicKey = :introPublicKey WHERE id = :id")
    suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray)

    @Query("DELETE FROM pending_chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_chats WHERE id IN (:ids)")
    suspend fun deleteForIds(ids: Set<String>)

    @Query("DELETE FROM pending_chats WHERE ownerIdentityId = :ownerIdentityId")
    suspend fun deleteOwner(ownerIdentityId: String)
}

/**
 * Room database housing [PersistedPendingChat].
 *
 * Its own `@Database`, like every other persistence domain here, so a
 * migration for the waiting room can't take groups or messages with it.
 *
 * ## Why this is persisted
 *
 * The offer half could have stayed in memory — a pushed offer is a
 * retained Nostr event the inbox fan-out re-delivers on every launch,
 * which is why `PendingInvitesStore` was process-lifetime. The *asking*
 * half cannot. When a link or QR join stopped opening a screen of its
 * own and started leaving a row in the chats list, that row became the
 * only evidence the person ever asked, and nothing replays it: the
 * request went out, the founder may take days, and a process death in
 * between would leave someone waiting on something their device has no
 * record of.
 */
@Database(entities = [PersistedPendingChat::class], version = 2, exportSchema = false)
abstract class PendingChatDatabase : RoomDatabase() {
    abstract fun pendingChatDao(): PendingChatDao
}

/**
 * Hand-rolled migrations for [PendingChatDatabase].
 *
 * v1 → v2 adds `encryptedJoinerLabel`. Room validates the schema hash on
 * open and throws when the entity has moved without the version, and
 * `fallbackToDestructiveMigration` only rescues a *version* mismatch —
 * so shipping the column without this bump crashes every device
 * carrying the previous build, on launch, until its data is cleared.
 *
 * Additive and nullable: existing rows decode to "no name asked under
 * yet", which is what a row written before the confirmation screen
 * existed means anyway.
 */
object PendingChatDatabaseMigrations {
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_chats ADD COLUMN encryptedJoinerLabel BLOB")
        }
    }
}
