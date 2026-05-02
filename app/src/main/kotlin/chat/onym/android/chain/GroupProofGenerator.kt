package chat.onym.android.chain

import chat.onym.android.group.GovernanceMember
import chat.onym.sdk.Common
import chat.onym.sdk.Tyranny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Output of [GroupProofGenerator.proveCreate]. The relayer / contract
 * expect:
 *
 *  - [proof] — 1568 bytes, the [Common.parsePlonkProof]-trimmed form
 *    of the raw 1601-byte SDK output (strips four `len()` u64 prefixes
 *    + the trailing `plookup_proof: None` byte). The unparsed shape
 *    is rejected on-chain.
 *  - [publicInputs] — the `(commitment, epoch)` pair the
 *    [SepCreateGroupV2Request] carries. For create the SDK's per-type
 *    PI bundle (`commitment(32) || Fr(0)(32) || admin_pubkey_commitment(32) || group_id_fr(32)`)
 *    is sliced to its first 32 bytes for the commitment; epoch is
 *    always 0 for create.
 *
 * Mirrors `GroupCreateProof` from onym-ios PR #25.
 */
data class GroupCreateProof(
    val proof: ByteArray,
    val publicInputs: SepPublicInputs,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupCreateProof) return false
        return proof.contentEquals(other.proof) && publicInputs == other.publicInputs
    }

    override fun hashCode(): Int =
        31 * proof.contentHashCode() + publicInputs.hashCode()
}

/**
 * Inputs for a create-group proof. The caller (PR-C interactor) is
 * responsible for:
 *
 *  - lex-sorting [members] by [GovernanceMember.publicKeyCompressed]
 *    BEFORE computing [adminIndex] (the SDK reuses the same sort to
 *    validate `memberLeafHashes[adminIndex] == leafHash(adminBlsSecretKey)`),
 *  - generating a fresh 32-byte [salt] via
 *    `chat.onym.android.group.GroupCommitmentBuilder.generateSalt`,
 *  - choosing the [tier] based on the expected member count.
 *
 * Mirrors `GroupProofCreateInput` from onym-ios PR #25.
 */
data class GroupProofCreateInput(
    val groupType: SepGroupType,
    val tier: SepTier,
    /** Lex-sorted by `publicKeyCompressed`. The packed `leafHash`es
     *  land in the prover in this order. */
    val members: List<GovernanceMember>,
    /** 32 bytes BE — the sender's own BLS Fr scalar. */
    val adminBlsSecretKey: ByteArray,
    /** Position of the admin in [members] (after the lex sort). */
    val adminIndex: Int,
    /** 32-byte raw group ID — used directly as the `group_id_fr`
     *  per-group binding scalar in the Tyranny circuit. */
    val groupId: ByteArray,
    /** 32 bytes; LE-mod-r in-circuit. */
    val salt: ByteArray,
)

/**
 * Failure modes for [GroupProofGenerator.proveCreate]. Sealed so
 * `when` exhaustiveness checks downstream don't need an `else`
 * branch.
 *
 * Mirrors `GroupProofGeneratorError` from onym-ios PR #25.
 */
sealed class GroupProofGeneratorError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Caller asked for a governance type other than [SepGroupType.TYRANNY].
     *  PR-B ships Tyranny only — UI surfaces this as a clear "TBD". */
    class NotYetSupported(val type: SepGroupType) :
        GroupProofGeneratorError("group type not yet supported: $type")

    /** [GroupProofCreateInput.adminIndex] was negative or `>= members.size`.
     *  Short-circuits before the JNI call so the SDK doesn't have to
     *  panic on the same condition. */
    class AdminIndexOutOfRange(val index: Int, val count: Int) :
        GroupProofGeneratorError("admin index $index out of range (members.size=$count)")

    /** Anything thrown out of the OnymSDK FFI — circuit-internal panic,
     *  malformed leaf hash, etc. Wraps the message but not the cause
     *  type because the SDK's exception hierarchy isn't part of our
     *  stable surface. */
    class SdkFailure(message: String) :
        GroupProofGeneratorError("OnymSDK proveCreate failed: $message")
}

/**
 * Chain seam for proof generation. Switches on
 * [GroupProofCreateInput.groupType] so PR-C's `CreateGroupInteractor`
 * doesn't need to import OnymSDK directly. Only [SepGroupType.TYRANNY]
 * is wired in this slice — the other governance types throw
 * [GroupProofGeneratorError.NotYetSupported].
 *
 * Mirrors `GroupProofGenerator` from onym-ios PR #25.
 */
interface GroupProofGenerator {
    /**
     * Generate a create-group PLONK proof + the on-wire commitment.
     *
     * **Runs on [Dispatchers.Default]** — the call is CPU-bound
     * (~3.5s on a Pixel 6 at depth 5) and would block any other
     * dispatcher's worker thread for the duration. Callers that want
     * to overlap with I/O should not switch dispatchers themselves.
     */
    suspend fun proveCreate(input: GroupProofCreateInput): GroupCreateProof
}

/**
 * Production [GroupProofGenerator] backed by `chat.onym.sdk.Tyranny`.
 *
 * Mirrors `OnymGroupProofGenerator` from onym-ios PR #25.
 */
class OnymGroupProofGenerator : GroupProofGenerator {

    override suspend fun proveCreate(input: GroupProofCreateInput): GroupCreateProof =
        when (input.groupType) {
            SepGroupType.TYRANNY -> proveTyrannyCreate(input)
            SepGroupType.ANARCHY,
            SepGroupType.ONE_ON_ONE,
            SepGroupType.DEMOCRACY,
            SepGroupType.OLIGARCHY,
                -> throw GroupProofGeneratorError.NotYetSupported(input.groupType)
        }

    private suspend fun proveTyrannyCreate(input: GroupProofCreateInput): GroupCreateProof {
        if (input.adminIndex < 0 || input.adminIndex >= input.members.size) {
            throw GroupProofGeneratorError.AdminIndexOutOfRange(
                index = input.adminIndex,
                count = input.members.size,
            )
        }
        val packedLeaves = ByteArray(input.members.size * 32)
        for ((i, member) in input.members.withIndex()) {
            require(member.leafHash.size == 32) {
                "leafHash for member $i has unexpected size ${member.leafHash.size}, expected 32"
            }
            System.arraycopy(member.leafHash, 0, packedLeaves, i * 32, 32)
        }

        // CPU-bound — keep it off Dispatchers.IO (which is for blocking
        // I/O) and off Dispatchers.Main. ~3.5s at depth 5 on a Pixel 6.
        return withContext(Dispatchers.Default) {
            val raw = try {
                Tyranny.proveCreate(
                    /* depth = */ input.tier.depth,
                    /* memberLeafHashes = */ packedLeaves,
                    /* adminSecretKey = */ input.adminBlsSecretKey,
                    /* adminIndex = */ input.adminIndex,
                    /* groupIdFr = */ input.groupId,
                    /* salt = */ input.salt,
                )
            } catch (e: Throwable) {
                throw GroupProofGeneratorError.SdkFailure(e.message ?: e.toString())
            }
            val parsed = try {
                Common.parsePlonkProof(raw.proof)
            } catch (e: Throwable) {
                throw GroupProofGeneratorError.SdkFailure(e.message ?: e.toString())
            }
            // PI bundle layout (Tyranny.CreateProof):
            //   commitment(32) || Fr(0)(32) || admin_pubkey_commitment(32) || group_id_fr(32)
            // Only the first 32 bytes (commitment) cross the wire; the
            // contract rederives the latter three from the proof itself.
            val commitment = raw.publicInputs.copyOfRange(0, 32)
            GroupCreateProof(
                proof = parsed,
                publicInputs = SepPublicInputs(commitment = commitment, epoch = 0uL),
            )
        }
    }
}
