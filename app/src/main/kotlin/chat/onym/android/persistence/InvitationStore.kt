package chat.onym.android.persistence

import java.time.Instant

/**
 * Persistence seam for incoming Nostr invitations. Implementations
 * provide CRUD over an opaque payload + a status field; encryption
 * at rest is the impl's responsibility (Room-backed impls wrap the
 * payload with [StorageEncryption] before the row hits SQLite).
 *
 * `suspend` everywhere — production calls use Room's coroutine
 * support; tests run against [chat.onym.android.support.InMemoryInvitationStore]
 * which serialises via `Mutex`.
 *
 * Mirrors the iOS `InvitationStore` protocol from onym-ios PR #16.
 */
interface InvitationStore {
    /** All persisted records, sorted by [IncomingInvitationRecord.receivedAt] desc. */
    suspend fun list(): List<IncomingInvitationRecord>

    /**
     * Persist a fresh inbound. Returns `true` if the row was newly
     * inserted; `false` if [IncomingInvitationRecord.id] was already
     * present (dedup). Callers use the return value to skip
     * redundant downstream notifications.
     */
    suspend fun save(record: IncomingInvitationRecord): Boolean

    /** No-op if [id] is absent. */
    suspend fun updateStatus(id: String, status: IncomingInvitationStatus)

    /** No-op if [id] is absent. */
    suspend fun delete(id: String)
}

/**
 * Plaintext value type the seam exposes. Persistence-side encryption
 * is invisible to callers — the [payload] is the unwrapped bytes
 * delivered by [InvitationStore.list].
 */
data class IncomingInvitationRecord(
    val id: String,
    val payload: ByteArray,
    val receivedAt: Instant,
    val status: IncomingInvitationStatus,
) {
    // Override equals / hashCode so tests can compare records (data
    // class auto-generated equals uses ByteArray reference equality).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingInvitationRecord) return false
        return id == other.id &&
            payload.contentEquals(other.payload) &&
            receivedAt == other.receivedAt &&
            status == other.status
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

/** Lifecycle of an invitation as the user interacts with it. */
enum class IncomingInvitationStatus {
    /** Just received; awaiting user decision. */
    Pending,
    /** User accepted; the joining-flow has consumed it. */
    Accepted,
    /** User dismissed; persisted only so we don't re-prompt on next inbound. */
    Rejected,
}
