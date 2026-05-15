package app.onym.android.group

import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sender-side pump for intro inbox subscriptions. Mirrors
 * [app.onym.android.inbox.IncomingInvitationsInteractor.runFanout]
 * but drops inbounds into [IntroRequestStore] instead of the
 * identity-inbox sink.
 *
 * **Why a parallel interactor instead of one shared seam**:
 * deliberately mirroring code rather than abstracting because the
 * two flows are likely to diverge — intro requests are interactive
 * (sender taps Approve), invitations are autonomic (joiner sees
 * the chat appear). Shared abstraction would leak through either
 * a generic-type parameter or a sink interface that adds
 * indirection without saving meaningful code.
 *
 * Subscription set is rebuilt wholesale on every emission via
 * `collectLatest`. Adding/revoking an intro key flips the active
 * subscription set within one emission window.
 *
 * **Each launched subscription captures its [IntroKeyEntry]**, so
 * inbounds can be tagged with the matching introPub directly —
 * no `tag → pub` reverse-lookup map needed. The pump holds the
 * mapping in scope for the duration of each subscription.
 */
class IntroInboxPump(
    private val transport: InboxTransport,
    private val store: IntroRequestStore,
    /** Maps an intro pubkey → its Nostr inbox tag. Production wires
     *  to `IdentityRepository.inboxTag(introPub)` (the same
     *  `SHA-256("sep-inbox-v1" || pub)[..8]` derivation identity
     *  inbox tags use); tests pass an identity-equality stub. */
    private val inboxTagFor: (introPublicKey: ByteArray) -> TransportInboxId,
) {

    /**
     * Run the fan-out for the lifetime of the calling scope. Each
     * [entries] emission cancels the prior subscription set
     * wholesale and re-subscribes.
     *
     * Production wires:
     * `introKeyStore.entriesFlow.map { it.filter { it.ownerIdentityId == active } }`
     * — switching identities or revoking an entry drops its
     * subscription within one emission window.
     */
    suspend fun runFanout(entries: Flow<List<IntroKeyEntry>>) {
        entries
            .map { list -> list.distinctBy { it.introPublicKey.toList() } }
            .distinctUntilChanged { old, new ->
                old.size == new.size &&
                    old.zip(new).all { (a, b) -> a.introPublicKey.contentEquals(b.introPublicKey) }
            }
            .collectLatest { current ->
                coroutineScope {
                    for (entry in current) {
                        launch { run(entry) }
                    }
                }
            }
    }

    /** Subscribe to one entry's inbox tag and pump every inbound
     *  into the store, tagged with the entry's introPubkey so
     *  PR-4's approval interactor can find the matching privkey. */
    private suspend fun run(entry: IntroKeyEntry) {
        val tag = inboxTagFor(entry.introPublicKey)
        transport.subscribe(tag).collect { inbound ->
            store.record(
                IntroRequest(
                    id = inbound.messageId,
                    targetIntroPublicKey = entry.introPublicKey,
                    payload = inbound.payload,
                    receivedAt = inbound.receivedAt,
                )
            )
        }
    }
}
