package app.onym.android.group

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one pending join request. Same plain-vs-encrypted split
 * as [PersistedGroup] / `PersistedMessage`: anything filtered or sorted
 * on stays plain, the sensitive bytes ride through `StorageEncryption`.
 *
 * Plain:
 *  - [id] — the Nostr event id, already the dedup key on the in-memory
 *    store. Primary key, so a relay replaying the same event on
 *    reconnect collapses onto the existing row.
 *  - [receivedAtMillis] — display sort order (newest-first).
 *  - [firstSeenAtMillis] — retention clock; see below.
 *
 * Encrypted:
 *  - [encryptedTargetIntroPublicKey] — correlates the request to a
 *    specific invite link; on its own it would tell anyone with the
 *    database file which invite a request came in on.
 *  - [encryptedPayload] — the sealed envelope. Already encrypted to the
 *    intro key, so this is belt-and-braces, but it costs nothing and
 *    keeps the column posture uniform across stores.
 */
@Entity(tableName = "intro_requests")
data class PersistedIntroRequest(
    @PrimaryKey val id: String,
    /**
     * The sender's claim of when they sent, used for display order
     * only. Sourced from the Nostr event's `ms` tag, which the *joiner*
     * writes — so it is an assertion, not an observation, and nothing
     * that decides whether a row lives or dies may read it.
     */
    val receivedAtMillis: Long,
    /**
     * When this device first wrote the row. Local clock, set once at
     * `record` and never updated.
     *
     * Retention prunes on this rather than [receivedAtMillis], because
     * that value is attacker- and clock-controlled. Pruning on it would
     * mean a founder offline past the retention window has every
     * replayed request swept the moment the app reads the store — never
     * seeing a request the joiner is still waiting on, which is the
     * failure this whole change exists to fix — and a joiner who stamps
     * `ms` far in the future gets a row that never expires.
     *
     * Nullable so adding it stays a non-destructive `ALTER TABLE ADD
     * COLUMN`; a null row is backfilled on next sight rather than swept.
     */
    val firstSeenAtMillis: Long? = null,
    val encryptedTargetIntroPublicKey: ByteArray,
    val encryptedPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedIntroRequest) return false
        return id == other.id &&
            receivedAtMillis == other.receivedAtMillis &&
            firstSeenAtMillis == other.firstSeenAtMillis &&
            encryptedTargetIntroPublicKey.contentEquals(other.encryptedTargetIntroPublicKey) &&
            encryptedPayload.contentEquals(other.encryptedPayload)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + receivedAtMillis.hashCode()
        h = 31 * h + (firstSeenAtMillis?.hashCode() ?: 0)
        h = 31 * h + encryptedTargetIntroPublicKey.contentHashCode()
        h = 31 * h + encryptedPayload.contentHashCode()
        return h
    }
}
