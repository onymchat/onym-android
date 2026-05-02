package chat.onym.android.group

import chat.onym.android.chain.AnchorSelectionKey
import chat.onym.android.chain.ContractNetwork
import chat.onym.android.chain.ContractsRepository
import chat.onym.android.chain.GovernanceType
import chat.onym.android.chain.GroupCreateProof
import chat.onym.android.chain.GroupProofCreateInput
import chat.onym.android.chain.GroupProofGenerator
import chat.onym.android.chain.GroupProofGeneratorError
import chat.onym.android.chain.OkHttpSepContractTransport
import chat.onym.android.chain.OnymGroupProofGenerator
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.SepContractClient
import chat.onym.android.chain.SepContractError
import chat.onym.android.chain.SepContractTransport
import chat.onym.android.chain.SepCreateGroupV2Request
import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepPublicInputs
import chat.onym.android.chain.SepTier
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.transport.InboxTransport
import chat.onym.android.transport.TransportInboxId
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Stateless orchestration for the create-group flow. Holds
 * dependencies only — every call to [create] is independent. The
 * view-model ([CreateGroupViewModel]) owns the form state and
 * progress; this type only knows how to drive a single end-to-end
 * run.
 *
 * ## Pipeline
 *
 *  1. Validate name + invitees (caller already parsed hex → 32-byte
 *     X25519 pubkeys).
 *  2. Resolve relayer URL ([RelayerRepository.selectUrl]) + contract
 *     binding ([ContractsRepository.binding]).
 *  3. Generate fresh `groupId` + `groupSecret` + `salt` (32 random
 *     bytes each).
 *  4. Build the creator's [GovernanceMember] from the device's BLS
 *     secret (single-member roster at creation; future invitees join
 *     later via `update_commitment`).
 *  5. Generate the Tyranny PLONK proof via [GroupProofGenerator].
 *  6. POST `create_group_v2` to the relayer; require
 *     `accepted == true`.
 *  7. Insert the group locally via [GroupRepository.insert] then
 *     [GroupRepository.markPublished] so a subscriber sees
 *     `isPublishedOnChain = true` immediately.
 *  8. For each invitee: encode + seal the [GroupInvitationPayload],
 *     send via [InboxTransport.send], require `acceptedBy >= 1`. The
 *     group is already saved on disk at this point — invitation
 *     failures throw [CreateGroupError.InvitationSendFailed] but
 *     leave the group durable so a future "retry invites" UI can
 *     pick it up.
 *
 * Mirrors `CreateGroupInteractor` from onym-ios PR #26.
 */
open class CreateGroupInteractor(
    private val identity: IdentityRepository,
    private val relayers: RelayerRepository,
    private val contracts: ContractsRepository,
    private val groups: GroupRepository,
    private val proofGenerator: GroupProofGenerator = OnymGroupProofGenerator(),
    private val inboxTransport: InboxTransport,
    /** Builds a [SepContractTransport] from the relayer URL chosen
     *  per-call. Injected so tests can swap in a fake without
     *  touching OkHttp. */
    private val makeContractTransport: (String) -> SepContractTransport = { url ->
        OkHttpSepContractTransport(httpClient = OkHttpClient(), endpointUrl = url)
    },
) {

    /**
     * Run the full pipeline. [onProgress] is called as each phase
     * starts; UI dispatches the update onto its own dispatcher.
     *
     * `open` so [CreateGroupViewModelTest] can subclass with a stub
     * that returns a canned [ChatGroup] without standing up the
     * real identity / relayer / contracts graph (those are exercised
     * end-to-end by `CreateGroupInteractorTest`).
     */
    open suspend fun create(
        name: String,
        invitees: List<ByteArray>,
        onProgress: (CreateGroupProgress) -> Unit = {},
    ): ChatGroup {
        // 1. Validate
        onProgress(CreateGroupProgress.Validating)
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw CreateGroupError.InvalidName
        for ((index, key) in invitees.withIndex()) {
            if (key.size != 32) throw CreateGroupError.InvalidInviteeKey(index)
        }

        // 2. Resolve relayer + contract binding
        val relayerUrl = relayers.selectUrl() ?: throw CreateGroupError.NoActiveRelayer
        val key = AnchorSelectionKey(network = ContractNetwork.Testnet, type = GovernanceType.Tyranny)
        val binding = contracts.snapshots.value.binding(key)
            ?: throw CreateGroupError.NoContractBinding(GovernanceType.Tyranny)

        // 3. Group params
        val groupId = randomBytes(32)
        val groupSecret = randomBytes(32)
        val salt = GroupCommitmentBuilder.generateSalt()
        val tier = SepTier.SMALL

        // 4. Creator member — the one BLS Fr scalar read outside
        // IdentityRepository this layer needs. The bytes pass straight
        // into Tyranny.proveCreate (via GroupProofGenerator) and into
        // computeLeafHash, then fall out of scope at the end of this
        // method. IdentityRepository pins the "do not retain" contract
        // on the accessor; this is the proof-generation hop the
        // contract authorises.
        val blsSecret = try {
            // onym:allow-secret-read
            identity.blsSecretKey()
        } catch (_: Throwable) {
            throw CreateGroupError.MissingIdentity
        }
        val identitySnapshot = identity.currentIdentity()
            ?: throw CreateGroupError.MissingIdentity
        val creatorMember = try {
            GovernanceMember(
                publicKeyCompressed = identitySnapshot.blsPublicKey,
                leafHash = GroupCommitmentBuilder.computeLeafHash(blsSecret),
            )
        } catch (e: Throwable) {
            throw CreateGroupError.SdkFailure(e.message ?: e.toString())
        }
        val members = listOf(creatorMember)  // single-member roster, already sorted

        // 5. Generate proof
        onProgress(CreateGroupProgress.Proving)
        val input = GroupProofCreateInput(
            groupType = SepGroupType.TYRANNY,
            tier = tier,
            members = members,
            adminBlsSecretKey = blsSecret,
            adminIndex = 0,
            groupId = groupId,
            salt = salt,
        )
        val proof: GroupCreateProof = try {
            proofGenerator.proveCreate(input)
        } catch (e: GroupProofGeneratorError) {
            throw CreateGroupError.ProofGenerationFailed(e)
        } catch (e: Throwable) {
            throw CreateGroupError.SdkFailure(e.message ?: e.toString())
        }

        // 6. Anchor on chain
        onProgress(CreateGroupProgress.Anchoring)
        val transport = makeContractTransport(relayerUrl)
        val client = SepContractClient(contractId = binding.contractId, transport = transport)
        val request = SepCreateGroupV2Request(
            caller = identitySnapshot.stellarAccountID,
            groupId = groupId,
            commitment = proof.publicInputs.commitment,
            tier = tier.rawValue.toUInt(),
            groupType = SepGroupType.TYRANNY,
            memberCount = members.size.toUInt(),
            proof = proof.proof,
            publicInputs = proof.publicInputs,
        )
        val response = try {
            client.createGroupV2(request)
        } catch (e: SepContractError) {
            throw CreateGroupError.AnchorTransport(e.message ?: "transport error")
        } catch (e: Throwable) {
            throw CreateGroupError.AnchorTransport(e.message ?: "unknown")
        }
        if (!response.accepted) {
            throw CreateGroupError.AnchorRejected(response.message)
        }

        // 7. Save locally
        val groupIdHex = groupId.toHex()
        val adminPubkeyHex = identitySnapshot.blsPublicKey.toHex()
        val group = ChatGroup(
            id = groupIdHex,
            name = trimmedName,
            groupSecret = groupSecret,
            createdAtMillis = System.currentTimeMillis(),
            members = members,
            epoch = 0uL,
            salt = salt,
            commitment = proof.publicInputs.commitment,
            tier = tier,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = adminPubkeyHex,
            isPublishedOnChain = false,
        )
        groups.insert(group)
        groups.markPublished(group.id, proof.publicInputs.commitment)

        // 8. Send invitations (group is already on disk; failures
        //    throw but a future "retry invites" UI can pick up the
        //    half-delivered state).
        if (invitees.isNotEmpty()) {
            onProgress(CreateGroupProgress.SendingInvitations(invitees.size))
            val payload = GroupInvitationPayload(
                version = 1,
                groupId = groupId,
                groupSecret = groupSecret,
                name = trimmedName,
                members = members,
                epoch = 0uL,
                salt = salt,
                commitment = proof.publicInputs.commitment,
                tierRaw = tier.rawValue,
                groupTypeRaw = SepGroupType.TYRANNY.rawValue,
                adminPubkeyHex = adminPubkeyHex,
            )
            val payloadBytes = try {
                jsonFormat.encodeToString(GroupInvitationPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) {
                throw CreateGroupError.InvitationEncodingFailed
            }
            for ((index, inboxKey) in invitees.withIndex()) {
                val sealed = try {
                    identity.sealInvitation(payloadBytes, inboxKey)
                } catch (e: Throwable) {
                    throw CreateGroupError.InvitationSendFailed(index, e.message ?: e.toString())
                }
                val tag = inboxTagFor(inboxKey)
                val receipt = try {
                    inboxTransport.send(sealed, TransportInboxId(tag))
                } catch (e: Throwable) {
                    throw CreateGroupError.InvitationSendFailed(index, e.message ?: e.toString())
                }
                if (receipt.acceptedBy < 1) {
                    throw CreateGroupError.InvitationSendFailed(
                        index,
                        "no relay accepted the invitation",
                    )
                }
            }
        }

        // Snapshot the freshly-anchored group from the repository so
        // the caller sees `isPublishedOnChain = true`.
        return groups.snapshots.value.firstOrNull { it.id == group.id }
            ?: group.copy(isPublishedOnChain = true)
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true }

        /** Same derivation as `Identity.inboxTag` — duplicated here
         *  because the helper is tied to a specific identity, and we
         *  need it for arbitrary recipient pubkeys. */
        fun inboxTagFor(inboxPublicKey: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("sep-inbox-v1".toByteArray(Charsets.UTF_8))
            md.update(inboxPublicKey)
            val hash = md.digest()
            return buildString(16) {
                for (i in 0 until 8) {
                    append("%02x".format(hash[i].toInt() and 0xFF))
                }
            }
        }

        fun randomBytes(count: Int): ByteArray =
            ByteArray(count).also { SecureRandom().nextBytes(it) }

        fun ByteArray.toHex(): String =
            buildString(size * 2) {
                for (b in this@toHex) append("%02x".format(b.toInt() and 0xFF))
            }
    }
}

/** Progress events the interactor reports during [CreateGroupInteractor.create]. */
sealed class CreateGroupProgress {
    object Validating : CreateGroupProgress()

    /** ~3.5s on a Pixel 6 at depth 5 — by far the heaviest phase. */
    object Proving : CreateGroupProgress()

    object Anchoring : CreateGroupProgress()

    data class SendingInvitations(val total: Int) : CreateGroupProgress()
}

/**
 * Failure modes for [CreateGroupInteractor.create]. Sealed so the
 * UI can `when`-exhaust and surface a precise message for each kind.
 *
 * Mirrors `CreateGroupError` from onym-ios PR #26.
 */
sealed class CreateGroupError(message: String) : Exception(message) {

    object InvalidName : CreateGroupError("Group name cannot be empty") {
        private fun readResolve(): Any = InvalidName
    }

    class InvalidInviteeKey(val index: Int) :
        CreateGroupError("Invitee #${index + 1} is not a 32-byte X25519 public key")

    object MissingIdentity : CreateGroupError("No identity is loaded — bootstrap or restore first") {
        private fun readResolve(): Any = MissingIdentity
    }

    object NoActiveRelayer : CreateGroupError("No relayer is configured") {
        private fun readResolve(): Any = NoActiveRelayer
    }

    class NoContractBinding(val type: GovernanceType) :
        CreateGroupError("No ${type.wireValue} contract is published yet — pick one in Settings → Anchors")

    class ProofGenerationFailed(val proofError: GroupProofGeneratorError) :
        CreateGroupError(proofError.message ?: "Proof generation failed")

    class AnchorTransport(reason: String) :
        CreateGroupError("Couldn't reach the relayer: $reason") {
        val reason: String = reason
    }

    class AnchorRejected(val serverMessage: String?) :
        CreateGroupError("Relayer rejected the create: ${serverMessage ?: "(no message)"}")

    object InvitationEncodingFailed :
        CreateGroupError("Couldn't encode the invitation payload") {
        private fun readResolve(): Any = InvitationEncodingFailed
    }

    class InvitationSendFailed(val index: Int, val reason: String) :
        CreateGroupError("Invitation #${index + 1} failed: $reason")

    class SdkFailure(reason: String) :
        CreateGroupError("SDK failure: $reason")
}
