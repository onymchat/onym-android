package chat.onym.android.group

import chat.onym.android.chain.AnarchyCreateGroupPayload
import chat.onym.android.chain.AnchorSelectionKey
import chat.onym.android.chain.CanonicalFr
import chat.onym.android.chain.ContractsRepository
import chat.onym.android.chain.GovernanceType
import chat.onym.android.chain.GroupCreateProof
import chat.onym.android.chain.GroupProofCreateInput
import chat.onym.android.chain.GroupProofGenerator
import chat.onym.android.chain.GroupProofGeneratorError
import chat.onym.android.chain.NetworkPreferenceProvider
import chat.onym.android.chain.OkHttpSepContractTransport
import chat.onym.android.chain.OneOnOneCreateGroupPayload
import chat.onym.android.chain.OnymGroupProofGenerator
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.SepContractClient
import chat.onym.android.chain.SepContractError
import chat.onym.android.chain.SepContractTransport
import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import chat.onym.android.chain.TyrannyCreateGroupPayload
import chat.onym.android.identity.Identity
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
    private val networkPreference: NetworkPreferenceProvider,
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
        groupType: SepGroupType = SepGroupType.TYRANNY,
        onProgress: (CreateGroupProgress) -> Unit = {},
    ): ChatGroup {
        // 1. Validate
        onProgress(CreateGroupProgress.Validating)
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw CreateGroupError.InvalidName
        for ((index, key) in invitees.withIndex()) {
            if (key.size != 32) throw CreateGroupError.InvalidInviteeKey(index)
        }
        // OneOnOne is a strict 2-party group — exactly 1 invitee
        // (the second party). Tyranny tolerates any count including
        // zero.
        if (groupType == SepGroupType.ONE_ON_ONE && invitees.size != 1) {
            throw CreateGroupError.OneOnOneRequiresExactlyOneInvitee(actual = invitees.size)
        }

        val governanceType = groupType.toGovernanceType()
            ?: throw CreateGroupError.UnsupportedGroupType(groupType)

        // 2. Resolve relayer + contract binding for the requested
        //    governance type. Same key drives both `binding.contractId`
        //    and the wire payload's top-level `contractType`.
        val relayerUrl = relayers.selectUrl() ?: throw CreateGroupError.NoActiveRelayer
        val activeNetwork = networkPreference.current()
        val key = AnchorSelectionKey(
            network = activeNetwork.contractNetwork,
            type = governanceType,
        )
        val binding = contracts.snapshots.value.binding(key)
            ?: throw CreateGroupError.NoContractBinding(governanceType)

        // 3. Group params.
        // `groupId` MUST be a canonical bls12-381 Fr (BE value < r) —
        // sep-tyranny's `is_canonical_fr(&group_id)` rejects anything
        // else with `Error::InvalidCommitmentEncoding` (#15). The check
        // exists to close a same-`group_id_fr` collision via
        // `group_id + p (mod 2^256)` — see the contract comment at
        // sep-tyranny/src/lib.rs:290–298. Run #25262987336 hit exactly
        // that with an `ea…` first byte from raw `SecureRandom.nextBytes`.
        val groupId = CanonicalFr.randomCanonicalFr32()
        val groupSecret = randomBytes(32)
        val salt = GroupCommitmentBuilder.generateSalt()
        val tier = SepTier.SMALL  // OneOnOne ignores tier (depth=5 hardcoded)

        // 4. Creator's BLS key. The bytes pass straight into the
        //    proof generator and into `computeLeafHash`, then fall
        //    out of scope at the end of this method.
        //    IdentityRepository pins the "do not retain" contract on
        //    the accessor; this is the proof-generation hop the
        //    contract authorises.
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
        val members = listOf(creatorMember)  // local creator-only roster

        // OneOnOne: generate a fresh ephemeral BLS Fr for the second
        // party and use it as `sk_1` in the founding ceremony. The
        // FFI rejects `sk_0 == sk_1`, so retry on the (vanishing)
        // chance the canonical sample collides with the creator's key.
        val secondaryBlsSecret: ByteArray? = if (groupType == SepGroupType.ONE_ON_ONE) {
            var candidate: ByteArray
            do {
                candidate = CanonicalFr.randomCanonicalFr32()
            } while (candidate.contentEquals(blsSecret))
            candidate
        } else {
            null
        }

        // 5. Generate proof
        onProgress(CreateGroupProgress.Proving)
        val proofInput = GroupProofCreateInput(
            groupType = groupType,
            tier = tier,
            members = members,
            adminBlsSecretKey = blsSecret,
            adminIndex = 0,
            groupId = groupId,
            salt = salt,
            secondaryBlsSecretKey = secondaryBlsSecret,
        )
        val proof: GroupCreateProof = try {
            proofGenerator.proveCreate(proofInput)
        } catch (e: GroupProofGeneratorError) {
            throw CreateGroupError.ProofGenerationFailed(e)
        } catch (e: Throwable) {
            throw CreateGroupError.SdkFailure(e.message ?: e.toString())
        }

        // 6. Anchor on chain — per-type payload + relayer call.
        onProgress(CreateGroupProgress.Anchoring)
        val transport = makeContractTransport(relayerUrl)
        val client = SepContractClient(
            contractID = binding.contractId,
            contractType = groupType,
            network = activeNetwork.sepNetwork,
            transport = transport,
        )
        val response = try {
            when (groupType) {
                SepGroupType.TYRANNY -> client.createGroupTyranny(
                    TyrannyCreateGroupPayload(
                        groupId = groupId,
                        commitment = proof.commitment,
                        tier = tier.rawValue,
                        adminPubkeyCommitment = proof.adminPubkeyCommitment,
                        proof = proof.proof,
                        publicInputs = proof.publicInputs,
                    ),
                )
                SepGroupType.ONE_ON_ONE -> client.createGroupOneOnOne(
                    OneOnOneCreateGroupPayload(
                        groupId = groupId,
                        commitment = proof.commitment,
                        proof = proof.proof,
                        publicInputs = proof.publicInputs,
                    ),
                )
                SepGroupType.ANARCHY -> client.createGroupAnarchy(
                    AnarchyCreateGroupPayload(
                        groupId = groupId,
                        commitment = proof.commitment,
                        tier = tier.rawValue,
                        // `member_count` is informational and the contract
                        // accepts `0` as the documented "not tracked"
                        // sentinel (per `sep-anarchy`'s `create_group` doc —
                        // "Operators who don't want to publish a count
                        // pass `0`"). Pass the sentinel so chain observers
                        // see only the tier, not the exact roster size at
                        // create time. The accurate count lives in the
                        // local model.
                        memberCount = 0,
                        proof = proof.proof,
                        publicInputs = proof.publicInputs,
                    ),
                )
                else -> throw CreateGroupError.UnsupportedGroupType(groupType)
            }
        } catch (e: SepContractError) {
            throw CreateGroupError.AnchorTransport(e.message ?: "transport error")
        } catch (e: CreateGroupError) {
            throw e
        } catch (e: Throwable) {
            throw CreateGroupError.AnchorTransport(e.message ?: "unknown")
        }
        if (!response.accepted) {
            throw CreateGroupError.AnchorRejected(response.message)
        }

        // 7. Save locally. For OneOnOne we don't surface an
        //    `adminPubkeyHex` (no admin role on chain); leaving it
        //    null lets the receiver-side decoder branch on it.
        val groupIdHex = groupId.toHex()
        val adminPubkeyHex = if (groupType == SepGroupType.TYRANNY) {
            identitySnapshot.blsPublicKey.toHex()
        } else {
            null
        }
        // Stamp the active identity at create time so the per-identity
        // chats filter in `GroupRepository.snapshots` includes it
        // automatically. There MUST be a current identity (we already
        // resolved `identitySnapshot` above; without one the bootstrap
        // step above would have thrown `MissingIdentity`).
        val ownerId = identity.currentIdentityId.value
            ?: throw CreateGroupError.MissingIdentity
        val creatorAlias = identity.identities.value
            .firstOrNull { it.id == ownerId }
            ?.name
            .orEmpty()
        val group = ChatGroup(
            id = groupIdHex,
            name = trimmedName,
            groupSecret = groupSecret,
            createdAtMillis = System.currentTimeMillis(),
            members = members,
            memberProfiles = creatorProfiles(identitySnapshot, creatorAlias),
            epoch = 0uL,
            salt = salt,
            commitment = proof.commitment,
            tier = tier,
            groupType = groupType,
            adminPubkeyHex = adminPubkeyHex,
            isPublishedOnChain = false,
            ownerIdentityId = ownerId.value,
        )
        groups.insert(group)
        groups.markPublished(group.id, proof.commitment)

        // 8. Send invitations. For OneOnOne the (sole) invitee gets
        //    the ephemeral `secondaryBlsSecret` so they can act as
        //    the second party of the immutable group.
        if (invitees.isNotEmpty()) {
            onProgress(CreateGroupProgress.SendingInvitations(invitees.size))
            for ((index, inboxKey) in invitees.withIndex()) {
                val invitePayload = GroupInvitationPayload(
                    version = 1,
                    groupId = groupId,
                    groupSecret = groupSecret,
                    name = trimmedName,
                    members = members,
                    epoch = 0uL,
                    salt = salt,
                    commitment = proof.commitment,
                    tierRaw = tier.rawValue,
                    groupTypeRaw = groupType.wireValue,
                    adminPubkeyHex = adminPubkeyHex,
                    inviteeBlsSecretKey = secondaryBlsSecret,
                    // Ship the creator's directory so the invitee
                    // sees the inviter by alias from the moment the
                    // group materializes (PR 83). takeIf-not-empty
                    // matches iOS; pre-PR-82 senders shipped null.
                    memberProfiles = group.memberProfiles.takeIf { it.isNotEmpty() },
                )
                val payloadBytes = try {
                    jsonFormat.encodeToString(
                        GroupInvitationPayload.serializer(),
                        invitePayload,
                    ).toByteArray(Charsets.UTF_8)
                } catch (_: Throwable) {
                    throw CreateGroupError.InvitationEncodingFailed
                }
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

    /** Bridge the wire-typed [SepGroupType] to the binding-key
     *  [GovernanceType]. Returns `null` for governance flavours that
     *  don't have a contract slot today (e.g. `democracy`,
     *  `oligarchy` until they ship). */
    private fun SepGroupType.toGovernanceType(): GovernanceType? = when (this) {
        SepGroupType.TYRANNY -> GovernanceType.Tyranny
        SepGroupType.ONE_ON_ONE -> GovernanceType.OneOnOne
        SepGroupType.ANARCHY -> GovernanceType.Anarchy
        SepGroupType.DEMOCRACY -> GovernanceType.Democracy
        SepGroupType.OLIGARCHY -> GovernanceType.Oligarchy
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true }

        /**
         * Single-entry seed for [ChatGroup.memberProfiles] at create
         * time. Maps the creator's lowercase BLS pubkey hex (96 chars)
         * to a [MemberProfile] carrying their current display name +
         * inbox public key. Mirrors `creatorProfiles(...)` in
         * `CreateGroupInteractor.swift`. Applied to all governance
         * paths (Tyranny / OneOnOne / Anarchy).
         */
        fun creatorProfiles(
            identitySnapshot: Identity,
            alias: String,
        ): Map<String, MemberProfile> {
            val key = identitySnapshot.blsPublicKey.toHex()
            // [Identity] has no display name field on Android — alias
            // is read once from the per-identity [IdentitySummary] at
            // create time. A later identity rename doesn't backfill
            // historical groups.
            return mapOf(key to MemberProfile(
                alias = alias,
                inboxPublicKey = identitySnapshot.inboxPublicKey,
            ))
        }

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

    class OneOnOneRequiresExactlyOneInvitee(val actual: Int) :
        CreateGroupError(
            "1-on-1 groups need exactly one invitee — you added $actual.",
        )

    class UnsupportedGroupType(val type: SepGroupType) :
        CreateGroupError("$type groups can't be created yet")

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
