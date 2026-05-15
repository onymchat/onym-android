package app.onym.android.support

import app.onym.android.transport.InboundInbox
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.PublishReceipt
import app.onym.android.transport.TransportEndpoint
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Recording [InboxTransport] with a configurable acceptedBy count
 * per call. Different from a "always accepts" fake — tests can
 * exercise the "no relay accepted" path by setting
 * [setReceiptAcceptedBy] to 0.
 *
 * Mirrors `ConfigurableInboxTransport` from onym-ios PR #26.
 */
class ConfigurableInboxTransport : InboxTransport {

    data class Send(val payload: ByteArray, val inbox: TransportInboxId) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Send) return false
            return payload.contentEquals(other.payload) && inbox == other.inbox
        }

        override fun hashCode(): Int = 31 * payload.contentHashCode() + inbox.hashCode()
    }

    private val mutex = Mutex()
    private val _sends = mutableListOf<Send>()
    private var receiptAcceptedBy: Int = 1
    /** When non-null, [send] throws this and records nothing. */
    private var sendThrow: Throwable? = null

    suspend fun sends(): List<Send> = mutex.withLock { _sends.toList() }

    suspend fun setReceiptAcceptedBy(count: Int) = mutex.withLock { receiptAcceptedBy = count }

    suspend fun setSendThrow(error: Throwable?) = mutex.withLock { sendThrow = error }

    override suspend fun connect(endpoints: List<TransportEndpoint>) {}
    override suspend fun disconnect() {}

    override suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt {
        val (acceptedBy, toThrow) = mutex.withLock {
            val pending = sendThrow
            if (pending == null) {
                _sends.add(Send(payload, inbox))
            }
            receiptAcceptedBy to pending
        }
        if (toThrow != null) throw toThrow
        return PublishReceipt(messageId = "fake-${UUID.randomUUID()}", acceptedBy = acceptedBy)
    }

    override fun subscribe(inbox: TransportInboxId): Flow<InboundInbox> = emptyFlow()

    override suspend fun unsubscribe(inbox: TransportInboxId) {}
}
