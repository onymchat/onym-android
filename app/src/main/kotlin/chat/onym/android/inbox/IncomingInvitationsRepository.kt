package chat.onym.android.inbox

import chat.onym.android.identity.ActiveIdentityProvider
import chat.onym.android.identity.IdentityId
import chat.onym.android.persistence.IncomingInvitationRecord
import chat.onym.android.persistence.IncomingInvitationStatus
import chat.onym.android.persistence.InvitationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Owns the in-memory view of "incoming invitations the user hasn't
 * dealt with yet" plus the corresponding writes to the persistence
 * seam.
 *
 * **Per-identity filter** (PR-6 of the deeplink-invite stack): the
 * cached store always holds the full on-disk roster across every
 * identity that's ever received an invite, so envelopes addressed
 * to a non-active identity don't get dropped while the user is on
 * a different one. [invitations] only emits rows whose
 * [IncomingInvitation.ownerIdentityId] matches the
 * currently-active identity. Switching identity (or removing one)
 * re-emits with the new filter applied.
 *
 * Touch-surface compliance:
 *
 *  - Holds an [InvitationStore] reference + an
 *    [ActiveIdentityProvider]. No transport, no OnymSDK, no
 *    Compose, no `Context`.
 *  - Exposes a [StateFlow] for observation; [recordIncoming] /
 *    [updateStatus] / [delete] / [removeForOwner] are the suspend
 *    mutators.
 *  - Mutations serialise through a [Mutex]; reads are lock-free off
 *    the [StateFlow].
 *
 * The shape mirrors `IdentityRepository` and `GroupRepository`
 * exactly — same Mutex + StateFlow + identity-listener pattern. iOS
 * uses `actor + AsyncStream` for the same role; Android stays with
 * [StateFlow] because Compose subscribes via
 * `collectAsStateWithLifecycle()` with no per-view boilerplate.
 *
 * Mirrors `IncomingInvitationsRepository.swift` from onym-ios PR
 * #16 + the per-identity filter from onym-ios PR #59.
 */
class IncomingInvitationsRepository(
    private val store: InvitationStore,
    private val identity: ActiveIdentityProvider,
    /** Scope owning the per-identity-selection collector. The
     *  collector lives for the process lifetime; pass an
     *  `applicationScope` (SupervisorJob + Dispatchers.IO). */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _invitations = MutableStateFlow<List<IncomingInvitation>>(emptyList())

    /** Hot stream of the **currently-selected identity's** incoming
     *  invitations, sorted by [IncomingInvitation.receivedAt] desc.
     *  Empty until [bootstrap] runs OR until an identity is selected
     *  — whichever comes first. New collectors get the current value
     *  immediately, then one new value per successful mutation
     *  (dedup-only saves don't push a redundant snapshot). */
    val invitations: StateFlow<List<IncomingInvitation>> = _invitations.asStateFlow()

    init {
        // Cascade-delete on identity removal — wipes the removed
        // identity's invitations from disk before the identity's
        // secrets are wiped. The removal-listener slot is multi-
        // listener (deeplink PR-3 flipped it from single → list).
        // Order: GroupRepository's chat-wipe runs first (registered
        // in its `init`), then this repository's invitation-wipe
        // (registered here), then the deeplink-invite intro-key
        // wipe (registered in OnymApplication wiring).
        identity.registerRemovalListener { id ->
            mutex.withLock {
                store.deleteForOwner(id.value)
                refreshLocked(identity.currentIdentityId.value)
            }
        }
    }

    /**
     * Start observing the active-identity flow. Idempotent — safe to
     * call from `onCreate`. Recomputes [invitations] every time the
     * active identity changes (including → null, which empties the
     * stream).
     */
    fun start() {
        scope.launch {
            identity.currentIdentityId.collectLatest { id ->
                mutex.withLock { refreshLocked(id) }
            }
        }
    }

    /**
     * Hydrate from disk. Idempotent — safe to call from any
     * `onCreate` / `LaunchedEffect`. Repeated calls reload but don't
     * error.
     */
    suspend fun bootstrap() = mutex.withLock {
        refreshLocked(identity.currentIdentityId.value)
    }

    /**
     * Persist a freshly-arrived inbound. No-op (and no snapshot
     * push) if [id] was already persisted — relays can replay
     * events across reconnects, so dedup-on-id is the correctness
     * property.
     *
     * [ownerIdentityId] is the identity whose inbox tag the fan-out
     * delivered this on. Stamped at receive time so PR-6's
     * per-identity decrypt path can route to the right private key
     * (otherwise envelopes addressed to identity B would sit
     * undecryptable while the user is on identity A).
     */
    suspend fun recordIncoming(
        id: String,
        payload: ByteArray,
        receivedAt: Instant,
        ownerIdentityId: IdentityId,
    ) = mutex.withLock {
        val record = IncomingInvitationRecord(
            id = id,
            payload = payload,
            receivedAt = receivedAt,
            status = IncomingInvitationStatus.Pending,
            ownerIdentityIdString = ownerIdentityId.value,
        )
        if (store.save(record)) {
            refreshLocked(identity.currentIdentityId.value)
        }
    }

    /** Update the status of an existing invitation; refresh the
     *  StateFlow snapshot. No-op for unknown ids (the store
     *  swallows them, and the snapshot rebuild is idempotent). */
    suspend fun updateStatus(id: String, status: IncomingInvitationStatus) = mutex.withLock {
        store.updateStatus(id, status)
        refreshLocked(identity.currentIdentityId.value)
    }

    /** Remove the invitation by id; refresh the StateFlow snapshot. */
    suspend fun delete(id: String) = mutex.withLock {
        store.delete(id)
        refreshLocked(identity.currentIdentityId.value)
    }

    /**
     * Cascade-delete every invitation owned by [ownerIdentityId].
     * Public surface for callers that need explicit (non-listener-
     * driven) wipes — tests, debug menus, or the future "wipe
     * everything for this identity" admin path.
     *
     * The init-time removal listener on
     * [ActiveIdentityProvider.registerRemovalListener] also calls
     * this internally; production wiping happens automatically
     * there.
     */
    suspend fun removeForOwner(ownerIdentityId: IdentityId): Int = mutex.withLock {
        val removed = store.deleteForOwner(ownerIdentityId.value)
        if (removed > 0) {
            refreshLocked(identity.currentIdentityId.value)
        }
        removed
    }

    private suspend fun refreshLocked(activeId: IdentityId?) {
        _invitations.value = if (activeId == null) {
            emptyList()
        } else {
            store.list()
                .filter { it.ownerIdentityIdString == activeId.value }
                .map(::toInvitation)
        }
    }

    private fun toInvitation(rec: IncomingInvitationRecord) = IncomingInvitation(
        id = rec.id,
        payload = rec.payload,
        receivedAt = rec.receivedAt,
        status = rec.status,
        ownerIdentityId = IdentityId(rec.ownerIdentityIdString),
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
    val ownerIdentityId: IdentityId,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingInvitation) return false
        return id == other.id &&
            payload.contentEquals(other.payload) &&
            receivedAt == other.receivedAt &&
            status == other.status &&
            ownerIdentityId == other.ownerIdentityId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + ownerIdentityId.hashCode()
        return result
    }
}
