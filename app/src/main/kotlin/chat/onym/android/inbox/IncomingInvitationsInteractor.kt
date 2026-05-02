package chat.onym.android.inbox

import chat.onym.android.transport.InboxTransport
import chat.onym.android.transport.TransportInboxId
import kotlinx.coroutines.flow.collect

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
 *  - One job: forward each [chat.onym.android.transport.InboundInbox]
 *    into [IncomingInvitationsRepository.recordIncoming]. Decryption
 *    + parsing of the inner payload belongs in a future
 *    `InvitationDetailsRepository` that owns the X25519 read; this
 *    interactor stays opaque-byte-shipping by design.
 *
 * Mirrors `IncomingInvitationsInteractor.swift` from onym-ios PR #16.
 */
class IncomingInvitationsInteractor(
    private val inboxTransport: InboxTransport,
    private val repository: IncomingInvitationsRepository,
) {

    /**
     * Subscribe to [inbox] and forward each delivered message into
     * the repository. Suspends for the lifetime of the subscription;
     * cancellation of the calling scope unsubscribes upstream
     * (production `NostrInboxTransport` runs `unsubscribe` from the
     * subscribe Flow's `awaitClose`).
     */
    suspend fun run(inbox: TransportInboxId) {
        inboxTransport.subscribe(inbox).collect { inbound ->
            repository.recordIncoming(
                id = inbound.messageId,
                payload = inbound.payload,
                receivedAt = inbound.receivedAt,
            )
        }
    }
}
