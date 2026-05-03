package chat.onym.android.chain

import chat.onym.android.group.GovernanceMember
import chat.onym.sdk.OneOnOne
import chat.onym.sdk.Tyranny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Output of [GroupProofGenerator.proveCreate]. The relayer / contract
 * expect:
 *
 *  - [proof] — the **raw 1601-byte PLONK proof** (the relayer's
 *    `decode_wire_bytes(_, _, Some(1601))` rejects anything else; the
 *    `parsePlonkProof` trim happens on the contract side, not on the
 *    wire).
 *  - [publicInputs] — the SDK's full per-type PI bundle, split into
 *    32-byte chunks. Tyranny create returns 4 chunks
 *    (`commitment || Fr(0) || admin_pubkey_commitment || group_id_fr`)
 *    which the relayer forwards as the contract's `Vec<BytesN<32>>`
 *    public-inputs argument.
 *  - [commitment] / [adminPubkeyCommitment] are convenience accessors
 *    so callers don't have to re-slice the bundle.
 *
 * Mirrors `GroupCreateProof` from onym-ios PR #27 (rewritten in the
 * follow-up to drop `parsePlonkProof` + flip to a 4-chunk PI vector).
 */
data class GroupCreateProof(
    /** Raw 1601-byte PLONK proof (relayer's
     *  `decode_wire_bytes(_, _, Some(1601))` rejects anything else;
     *  the `parsePlonkProof` trim happens on the contract side, not
     *  on the wire). */
    val proof: ByteArray,
    /** SDK's full per-type PI bundle, split into 32-byte chunks.
     *
     *  - Tyranny create returns 4:
     *    `commitment || Fr(0) || admin_pubkey_commitment || group_id_fr`.
     *  - OneOnOne create returns 2:
     *    `commitment || Fr(0)` (the contract's symmetric 1v1 PI shape). */
    val publicInputs: List<ByteArray>,
) {
    /** First 32 bytes of the PI bundle — the new commitment the
     *  contract will store. */
    val commitment: ByteArray get() = publicInputs[0]

    /** Bytes 64..96 of the PI bundle (Tyranny only) — the Poseidon
     *  commitment to the admin's BLS pubkey, surfaced separately
     *  because the relayer needs it both as a top-level CLI arg and
     *  as `publicInputs[2]`. */
    val adminPubkeyCommitment: ByteArray get() = publicInputs[2]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupCreateProof) return false
        return proof.contentEquals(other.proof) &&
            publicInputs.size == other.publicInputs.size &&
            publicInputs.zip(other.publicInputs).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var h = proof.contentHashCode()
        for (p in publicInputs) h = 31 * h + p.contentHashCode()
        return h
    }
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
     *  per-group binding scalar in the Tyranny circuit. MUST be a
     *  canonical BLS12-381 Fr scalar (see [CanonicalFr.isCanonical]);
     *  callers should generate via [CanonicalFr.randomCanonicalFr32]. */
    val groupId: ByteArray,
    /** 32 bytes; LE-mod-r in-circuit. */
    val salt: ByteArray,
    /** 32 BE Fr — only required when [groupType] is
     *  [SepGroupType.ONE_ON_ONE]: the SECOND party's BLS secret key,
     *  fed into `OneOnOne.proveCreate(sk_0, sk_1, salt)` as `sk_1`.
     *  The OneOnOne FFI rejects `sk_0 == sk_1`, so the creator MUST
     *  generate a fresh ephemeral scalar (NOT reuse [adminBlsSecretKey])
     *  and ship it to the invitee inside the sealed invitation envelope.
     *  `null` for any non-OneOnOne governance type. */
    val secondaryBlsSecretKey: ByteArray? = null,
)

/**
 * Failure modes for [GroupProofGenerator.proveCreate]. Sealed so
 * `when` exhaustiveness checks downstream don't need an `else`
 * branch.
 *
 * Mirrors `GroupProofGeneratorError` from onym-ios PR #25.
 */
sealed class GroupProofGeneratorError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Caller asked for a governance type the proof generator hasn't
     *  wired yet (currently: ANARCHY, DEMOCRACY, OLIGARCHY).
     *  UI surfaces this as a clear "TBD". */
    class NotYetSupported(val type: SepGroupType) :
        GroupProofGeneratorError("group type not yet supported: $type")

    /** Caller asked for a [SepGroupType.ONE_ON_ONE] proof but did
     *  not supply [GroupProofCreateInput.secondaryBlsSecretKey]. The
     *  OneOnOne circuit needs both parties' Fr scalars in the witness
     *  (and the FFI rejects `sk_0 == sk_1`), so the second key is
     *  load-bearing and not derivable from the admin secret alone. */
    object SecondaryBlsSecretKeyRequired :
        GroupProofGeneratorError(
            "OneOnOne create requires a second BLS Fr scalar — caller must " +
                "generate a fresh ephemeral key and pass it as secondaryBlsSecretKey",
        ) {
        private fun readResolve(): Any = SecondaryBlsSecretKeyRequired
    }

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
            SepGroupType.ONE_ON_ONE -> proveOneOnOneCreate(input)
            SepGroupType.ANARCHY,
            SepGroupType.DEMOCRACY,
            SepGroupType.OLIGARCHY,
                -> throw GroupProofGeneratorError.NotYetSupported(input.groupType)
        }

    private suspend fun proveOneOnOneCreate(input: GroupProofCreateInput): GroupCreateProof {
        val sk1 = input.secondaryBlsSecretKey
            ?: throw GroupProofGeneratorError.SecondaryBlsSecretKeyRequired

        // CPU-bound — keep it off Dispatchers.IO and off Dispatchers.Main.
        // The OneOnOne circuit is depth-5 (same as Tyranny SMALL), so
        // the wall-time profile is comparable.
        return withContext(Dispatchers.Default) {
            val raw = try {
                OneOnOne.proveCreate(
                    /* secretKey0 = */ input.adminBlsSecretKey,
                    /* secretKey1 = */ sk1,
                    /* salt = */ input.salt,
                )
            } catch (e: Throwable) {
                throw GroupProofGeneratorError.SdkFailure(e.message ?: e.toString())
            }
            // OneOnOne's CreateProof returns (proof, commitment) as
            // separate buffers — unlike Tyranny's bundled 128-byte PI
            // blob. The contract still expects a Vec<BytesN<32>> PI
            // argument, so we synthesize the 2-element shape the
            // sep-oneonone circuit verifies: [commitment, fr_zero].
            // (`fr_zero` is the symmetric placeholder — see
            // `onym-contracts/plonk/sep-oneonone/src/lib.rs`.)
            GroupCreateProof(
                proof = raw.proof,
                publicInputs = listOf(raw.commitment, ByteArray(32)),
            )
        }
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
            // SDK returns the raw 1601-byte proof. Don't
            // parsePlonkProof — the relayer's
            // `decode_wire_bytes(_, _, Some(1601))` rejects the
            // trimmed form (the 1568-byte parse happens on the
            // contract side, not on the wire).
            //
            // PI bundle layout (`Tyranny.CreateProof.publicInputs`, 128 B):
            //   commitment(32) || Fr(0)(32) || admin_pubkey_commitment(32) || group_id_fr(32)
            // Each 32-byte chunk maps to one `BytesN<32>` in the
            // contract's `Vec<BytesN<32>>` public-inputs argument.
            val bundle = raw.publicInputs
            if (bundle.size != 128) {
                throw GroupProofGeneratorError.SdkFailure(
                    "expected 128-byte PI bundle, got ${bundle.size}",
                )
            }
            val chunks = (0 until 128 step 32).map { offset ->
                bundle.copyOfRange(offset, offset + 32)
            }
            GroupCreateProof(proof = raw.proof, publicInputs = chunks)
        }
    }
}
