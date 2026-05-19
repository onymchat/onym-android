package app.onym.android.group

import app.onym.android.identity.IdentityRepository
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
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
        // PR 88: ship the Poseidon leaf hash so the admin can run
        // Tyranny.proveUpdate without having to derive it again.
        // Computation goes through the FFI; pull the BLS secret only
        // for the duration of the call, never retain.
        val leafHash = try {
            // onym:allow-secret-read
            val sk = identity.blsSecretKey()
            GroupCommitmentBuilder.computeLeafHash(sk)
        } catch (_: Throwable) {
            null
        }
        val payload = JoinRequestPayload(
            joinerInboxPublicKey = activeIdentity.inboxPublicKey,
            // Stable cross-device identifier — the admin keys the
            // joiner into the local roster under this. Pre-PR-78
            // joiners shipped without it; post-PR-78 ships it always.
            joinerBlsPublicKey = activeIdentity.blsPublicKey,
            joinerLeafHash = leafHash,
            // PR A3: 32-byte Ed25519 envelope-signing pubkey. Hard-
            // cutover required; PR A4's chat dispatcher needs this on
            // every join request to verify the joiner's future chat
            // envelope signatures.
            joinerSendingPublicKey = activeIdentity.stellarPublicKey,
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
