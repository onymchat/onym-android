package chat.onym.android.group

import chat.onym.android.identity.IdentityRepository
import chat.onym.android.transport.InboxTransport
import chat.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Joiner-side: tap-the-deeplink → ship a sealed
 * [JoinRequestPayload] to the inviter's intro inbox.
 *
 * Flow:
 *  1. Build the payload (joiner's inbox pubkey + display label +
 *     group id echo).
 *  2. Seal the payload to [IntroCapability.introPublicKey] using
 *     the existing [IdentityRepository.sealInvitation] (X25519
 *     ECDH against intro_pub + AES-GCM + Ed25519 signature with
 *     the joiner's long-term key).
 *  3. POST the sealed bytes to the Nostr inbox tag derived from
 *     intro_pub.
 *  4. Surface success/failure to the JoinScreen UI (PR-7).
 */
class JoinRequestSender(
    private val identity: IdentityRepository,
    private val inboxTransport: InboxTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    sealed class Outcome {
        object Sent : Outcome()
        class NoIdentityLoaded : Outcome()
        class TransportFailed(val reason: String) : Outcome()
    }

    /**
     * @param capability decoded from the deeplink's `?c=…` payload.
     * @param joinerDisplayLabel surfaced in the inviter's approval
     *        prompt. Joiner-controlled untrusted text — keep short
     *        (Nostr relays typically cap event size at ~64KB and
     *        we don't want to bloat the request envelope).
     */
    suspend fun send(
        capability: IntroCapability,
        joinerDisplayLabel: String,
    ): Outcome = withContext(ioDispatcher) {
        val activeIdentity = identity.currentIdentity() ?: return@withContext Outcome.NoIdentityLoaded()
        val payload = JoinRequestPayload(
            joinerInboxPublicKey = activeIdentity.inboxPublicKey,
            joinerDisplayLabel = joinerDisplayLabel,
            groupId = capability.groupId,
        )
        val payloadBytes = jsonFormat.encodeToString(JoinRequestPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val sealed = try {
            identity.sealInvitation(payloadBytes, capability.introPublicKey)
        } catch (e: Throwable) {
            return@withContext Outcome.TransportFailed("seal: ${e.message ?: e.javaClass.simpleName}")
        }
        val tag = TransportInboxId(IdentityRepository.inboxTag(capability.introPublicKey))
        val receipt = try {
            inboxTransport.send(sealed, tag)
        } catch (e: Throwable) {
            return@withContext Outcome.TransportFailed("send: ${e.message ?: e.javaClass.simpleName}")
        }
        if (receipt.acceptedBy < 1) {
            return@withContext Outcome.TransportFailed("no relay accepted the request")
        }
        Outcome.Sent
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true }
    }
}
