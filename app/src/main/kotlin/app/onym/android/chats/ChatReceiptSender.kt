package app.onym.android.chats

import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Seals + ships [ChatReceiptPayload]s back to a message's sender.
 * Used by the dispatcher (delivered receipts on receive) and the chat
 * thread (read receipts on view). Best-effort: a failed seal or send
 * is swallowed — a missing receipt only costs a check mark, never
 * correctness.
 *
 * Mirrors `ChatReceiptSender.swift` from onym-ios.
 */
interface ChatReceiptSending {
    suspend fun send(
        kind: ChatReceiptPayload.Kind,
        messageIds: List<UUID>,
        groupId: ByteArray,
        recipientInboxKey: ByteArray,
    )
}

/** Default no-op so dispatcher / VM test constructions that don't
 *  exercise receipts can leave it unset. */
class NoopChatReceiptSender : ChatReceiptSending {
    override suspend fun send(
        kind: ChatReceiptPayload.Kind,
        messageIds: List<UUID>,
        groupId: ByteArray,
        recipientInboxKey: ByteArray,
    ) = Unit
}

class ChatReceiptSender(
    private val activeIdentity: ActiveIdentityProvider,
    private val identitiesFlow: StateFlow<List<IdentitySummary>>,
    private val envelopeSealer: InvitationEnvelopeSealer,
    private val inboxTransport: InboxTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatReceiptSending {

    override suspend fun send(
        kind: ChatReceiptPayload.Kind,
        messageIds: List<UUID>,
        groupId: ByteArray,
        recipientInboxKey: ByteArray,
    ) = withContext(ioDispatcher) {
        if (messageIds.isEmpty()) return@withContext
        val activeId = activeIdentity.currentIdentityId.value ?: return@withContext
        val me = identitiesFlow.value.firstOrNull { it.id == activeId } ?: return@withContext
        val payload = ChatReceiptPayload(
            version = 1,
            groupId = groupId,
            senderBlsPubkeyHex = me.blsPublicKey.toHexLowercase(),
            kind = kind,
            messageIds = messageIds,
        )
        val bytes = try {
            jsonFormat.encodeToString(ChatReceiptPayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            return@withContext
        }
        val sealed = try {
            envelopeSealer.sealInvitation(bytes, recipientInboxKey)
        } catch (_: Throwable) {
            return@withContext
        }
        val tag = TransportInboxId(IdentityRepository.inboxTag(recipientInboxKey))
        try {
            inboxTransport.send(sealed, tag)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true }

        fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
