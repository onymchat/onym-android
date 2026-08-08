package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Stateless transport-to-persistence pump. Holds two refs — the
 * upstream [InboxTransport] seam and the downstream
 * [IncomingInvitationsRepository] — and connects them in [run].
 *
 * Touch-surface compliance:
 *
 *  - No state of its own. Cancellation comes from the caller's
 *    coroutine scope; there's no `start` / `stop` surface to forget
 *    to clean up.
 *  - No OnymSDK. No `Context`. No persistence backend.
 *  - One job: forward each [app.onym.android.transport.InboundInbox]
 *    into [IncomingInvitationsRepository.recordIncoming]. Decryption
 *    + parsing of the inner payload belongs in
 *    [InvitationDecryptor]; this interactor stays opaque-byte-
 *    shipping by design.
 *
 * Mirrors `IncomingInvitationsInteractor.swift` from onym-ios PR
 * #16 + the per-identity-keyed fan-out from onym-ios PR #59.
 */
class IncomingInvitationsInteractor(
    private val inboxTransport: InboxTransport,
    private val repository: IncomingInvitationsRepository,
    /**
     * Optional receive-side dispatcher (PR 80+). When set, every
     * inbound message is handed to the dispatcher before falling
     * back to the legacy invitations queue. The dispatcher decrypts
     * once at receive time + routes:
     *  - `MemberAnnouncementPayload` → applied to the local group.
     *  - `GroupInvitationPayload` → materialized into a new group (PR 83).
     *  - Anything else → falls through into [repository.recordIncoming].
     *
     * Null preserves pre-PR-80 behavior (always queue).
     */
    private val dispatcher: IncomingMessageDispatcher? = null,
) {

    /**
     * Subscribe to [inbox] and forward each delivered message into
     * the dispatcher (when wired) or directly into the repository.
     * Each record is stamped with [ownerIdentityId] so the
     * per-identity decrypt path can route to the right X25519
     * private key.
     *
     * Suspends for the lifetime of the subscription; cancellation
     * of the calling scope unsubscribes upstream (production
     * `NostrInboxTransport` runs `unsubscribe` from the subscribe
     * Flow's `awaitClose`).
     */
    suspend fun run(inbox: TransportInboxId, ownerIdentityId: IdentityId) {
        inboxTransport.subscribe(inbox).collect { inbound ->
            if (dispatcher != null) {
                dispatcher.dispatch(
                    messageId = inbound.messageId,
                    ownerIdentityId = ownerIdentityId,
                    payload = inbound.payload,
                    receivedAt = inbound.receivedAt,
                )
            } else {
                repository.recordIncoming(
                    id = inbound.messageId,
                    payload = inbound.payload,
                    receivedAt = inbound.receivedAt,
                    ownerIdentityId = ownerIdentityId,
                )
            }
        }
    }

    /**
     * Multi-identity fan-out. Subscribes to **every** entry in the
     * latest [entries] emission concurrently — one child coroutine
     * per identity inside a structured-concurrency scope. When the
     * upstream flow emits a new list (identity added / removed /
     * selected), the old subscription set is cancelled wholesale
     * and a fresh set is launched.
     *
     * Each emission is a list of `(IdentityId, TransportInboxId)`
     * pairs so each launched subscription captures the identity
     * the inbox tag belongs to and stamps inbounds with that ID
     * on the way to [IncomingInvitationsRepository.recordIncoming].
     * The identity ID is what makes per-identity decryption routing
     * work later — without it, the decryptor can't tell which
     * X25519 key the envelope is addressed to.
     *
     * Fan-out is the right shape for multi-identity: messages sent
     * to any identity's inbox MUST land on disk regardless of
     * which identity the user is currently viewing — otherwise
     * switching identity would silently drop messages received
     * under the others.
     *
     * Suspends for the lifetime of the calling scope. Cancelling
     * the caller cancels every subscription.
     */
    suspend fun runFanout(entries: Flow<List<Pair<IdentityId, TransportInboxId>>>) {
        entries.distinctUntilChanged().collectLatest { current ->
            // Inner `coroutineScope` is the structured-concurrency
            // anchor for THIS emission. `collectLatest` cancels the
            // outer lambda on the next emission, which propagates
            // cancellation to the inner scope, which cancels every
            // child `launch` — the wholesale-resubscribe semantics.
            // The inner scope never returns under happy operation
            // (each `run(tag)` collects a never-completing Flow), so
            // it stays alive until cancelled.
            coroutineScope {
                for ((identityId, tag) in current) {
                    launch { run(tag, identityId) }
                }
            }
        }
    }
}
