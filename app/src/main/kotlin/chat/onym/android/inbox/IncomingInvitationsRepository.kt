package chat.onym.android.inbox

import chat.onym.android.persistence.IncomingInvitationRecord
import chat.onym.android.persistence.IncomingInvitationStatus
import chat.onym.android.persistence.InvitationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Owns the in-memory view of "incoming invitations the user hasn't
 * dealt with yet" plus the corresponding writes to the persistence
 * seam. Touch-surface compliance:
 *
 *  - Holds **only** an [InvitationStore] reference. No transport,
 *    no OnymSDK, no Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [recordIncoming] /
 *    [updateStatus] / [delete] are the suspend mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free off
 *    the [StateFlow].
 *
 * The shape mirrors `IdentityRepository` exactly — same Mutex +
 * StateFlow + asStateFlow pattern. iOS uses `actor + AsyncStream`
 * for the same role; Android stays with [StateFlow] because Compose
 * subscribes via `collectAsStateWithLifecycle()` with no per-view
 * boilerplate (see *Why `StateFlow` instead of `actor + AsyncStream`*
 * in `README.md`).
 *
 * Mirrors `IncomingInvitationsRepository.swift` from onym-ios PR #16.
 */
class IncomingInvitationsRepository(
    private val store: InvitationStore,
) {
    private val mutex = Mutex()
    private val _invitations = MutableStateFlow<List<IncomingInvitation>>(emptyList())

    /** Hot stream of incoming invitations, sorted by [IncomingInvitation.receivedAt]
     *  desc. Empty until [bootstrap] runs. New collectors get the
     *  current value immediately, then one new value per successful
     *  mutation (dedup-only saves don't push a redundant snapshot). */
    val invitations: StateFlow<List<IncomingInvitation>> = _invitations.asStateFlow()

    /**
     * Hydrate from disk. Idempotent — safe to call from any
     * `onCreate` / `LaunchedEffect`. Repeated calls reload but don't
     * error.
     */
    suspend fun bootstrap() = mutex.withLock {
        _invitations.value = store.list().map(::toInvitation)
    }

    /**
     * Persist a freshly-arrived inbound. No-op (and no snapshot
     * push) if [id] was already persisted — relays can replay events
     * across reconnects, so dedup-on-id is the correctness property.
     */
    suspend fun recordIncoming(
        id: String,
        payload: ByteArray,
        receivedAt: Instant,
    ) = mutex.withLock {
        val record = IncomingInvitationRecord(
            id = id,
            payload = payload,
            receivedAt = receivedAt,
            status = IncomingInvitationStatus.Pending,
        )
        if (store.save(record)) {
            _invitations.value = store.list().map(::toInvitation)
        }
    }

    /** Update the status of an existing invitation; refresh the
     *  StateFlow snapshot. No-op for unknown ids (the store
     *  swallows them, and the snapshot rebuild is idempotent). */
    suspend fun updateStatus(id: String, status: IncomingInvitationStatus) = mutex.withLock {
        store.updateStatus(id, status)
        _invitations.value = store.list().map(::toInvitation)
    }

    /** Remove the invitation by id; refresh the StateFlow snapshot. */
    suspend fun delete(id: String) = mutex.withLock {
        store.delete(id)
        _invitations.value = store.list().map(::toInvitation)
    }

    private fun toInvitation(rec: IncomingInvitationRecord) = IncomingInvitation(
        id = rec.id,
        payload = rec.payload,
        receivedAt = rec.receivedAt,
        status = rec.status,
    )
}

/**
 * Public-facing snapshot type the repository emits. Structurally
 * identical to [IncomingInvitationRecord] today; kept separate so
 * the persistence-layer record can grow Room-specific fields
 * (encrypted columns, indexed-only joins, etc.) without leaking
 * into the repository's API surface.
 */
data class IncomingInvitation(
    val id: String,
    val payload: ByteArray,
    val receivedAt: Instant,
    val status: IncomingInvitationStatus,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingInvitation) return false
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
