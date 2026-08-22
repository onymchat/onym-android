package app.onym.android.group

import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentityId
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
 *  4. Report the outcome to the caller, which records it on the
 *     pending chat row — the screen that used to ask for this is gone.
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

        /**
         * The invitation carries rules and the caller passed no
         * agreement — a wiring mistake, not a person who declined.
         *
         * Its own outcome rather than a [TransportFailed], because the
         * two differ in what the row should then offer: nothing left the
         * device and nothing will, so "Ask again" would retry a call
         * that cannot start succeeding.
         */
        object RulesAgreementMissing : Outcome()
    }

    /**
     * @param capability decoded from the deeplink's `?c=…` payload.
     * @param joinerDisplayLabel surfaced in the inviter's approval
     *        prompt. Joiner-controlled untrusted text — keep short
     *        (Nostr relays typically cap event size at ~64KB and
     *        we don't want to bloat the request envelope).
     * @param ownerIdentityId the identity this request is sent as, and
     *        the one whose key signs the agreement. Named rather than
     *        read from the selection, so switching identities mid-flight
     *        cannot attribute an agreement to the wrong person.
     * @param agreedRules the rules text the joiner was shown and
     *        accepted, or null when the invitation carried none.
     *
     *        No default value on purpose. The whole reason it is a
     *        parameter is that it cannot be derived from [capability],
     *        so a default would make "forgot to pass it" the quiet
     *        answer — and the quiet answer reaches the founder as a
     *        joiner who declined to agree.
     *
     *        Passed in rather than read off [capability], because the
     *        signature has to cover what a person actually saw. The two
     *        are the same for a link, but an invitation pushed to this
     *        device carries its rules on the stored offer instead, and a
     *        sender that reached for the capability's copy would sign
     *        text that was never on screen in that case.
     */
    suspend fun send(
        capability: IntroCapability,
        joinerDisplayLabel: String,
        ownerIdentityId: IdentityId,
        agreedRules: String?,
    ): Outcome = withContext(ioDispatcher) {
        val ownerIdentity = identity.identities.value.firstOrNull { it.id == ownerIdentityId }
            ?: return@withContext Outcome.NoIdentityLoaded()
        // PR 88: ship the Poseidon leaf hash so the admin can run
        // Tyranny.proveUpdate without having to derive it again.
        // Computation goes through the FFI; pull the BLS secret only
        // for the duration of the call, never retain.
        val leafHash = try {
            // onym:allow-secret-read
            val sk = identity.blsSecretKey(ownerIdentityId)
            GroupCommitmentBuilder.computeLeafHash(sk)
        } catch (_: Throwable) {
            null
        }
        // The agreement, when there is one to make. Signed with the same
        // long-term key the request already announces as
        // `joinerSendingPublicKey`, so every member who is later told
        // about this joiner can check it — not just the founder who
        // admitted them.
        // An invitation that carried rules and a send with none is a
        // wiring mistake, not a person who declined — and the two are
        // indistinguishable by the time they reach the founder. Fail
        // here, where it is still a bug report.
        if (capability.rules != null && GroupRules.normalized(agreedRules) == null) {
            return@withContext Outcome.RulesAgreementMissing
        }
        var rulesHash: ByteArray? = null
        var rulesSignature: ByteArray? = null
        GroupRules.normalized(agreedRules)?.let { rules ->
            val hash = GroupRules.hash(rules)
            try {
                rulesSignature = identity.signWithStellarKeyAs(
                    ownerIdentityId,
                    GroupRules.statement(
                        groupId = capability.groupId,
                        rulesHash = hash,
                        joinerSendingPublicKey = ownerIdentity.sendingPublicKey,
                    ),
                )
                rulesHash = hash
            } catch (e: Throwable) {
                // Nothing is sent unsigned behind the person's back: they
                // were shown rules and told that Send agrees to them, and
                // a request that arrives without the signature reads to
                // the founder as someone who declined to agree.
                return@withContext Outcome.TransportFailed(
                    "rules signature: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
        val payload = JoinRequestPayload(
            joinerInboxPublicKey = ownerIdentity.inboxPublicKey,
            // Stable cross-device identifier — the admin keys the
            // joiner into the local roster under this. Pre-PR-78
            // joiners shipped without it; post-PR-78 ships it always.
            joinerBlsPublicKey = ownerIdentity.blsPublicKey,
            joinerLeafHash = leafHash,
            // PR A3: 32-byte Ed25519 envelope-signing pubkey. Hard-
            // cutover required; PR A4's chat dispatcher needs this on
            // every join request to verify the joiner's future chat
            // envelope signatures.
            joinerSendingPublicKey = ownerIdentity.sendingPublicKey,
            joinerDisplayLabel = joinerDisplayLabel,
            groupId = capability.groupId,
            rulesHash = rulesHash,
            rulesSignature = rulesSignature,
        )
        val payloadBytes = jsonFormat.encodeToString(JoinRequestPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val sealed = try {
            identity.sealInvitationAs(ownerIdentityId, payloadBytes, capability.introPublicKey)
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
