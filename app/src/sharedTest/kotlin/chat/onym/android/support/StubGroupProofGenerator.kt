package chat.onym.android.support

import chat.onym.android.chain.GroupCreateProof
import chat.onym.android.chain.GroupProofCreateInput
import chat.onym.android.chain.GroupProofGenerator
import chat.onym.android.chain.GroupProofGeneratorError
import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepPublicInputs

/**
 * Returns a deterministic 1568-byte "proof" without actually proving.
 * Skips the ~3.5s real prover so the interactor test suite stays
 * fast — the full real prove path is exercised by
 * `GroupProofGeneratorFfiTest` from PR-B.
 *
 * Mirrors `StubGroupProofGenerator` from onym-ios PR #26.
 */
class StubGroupProofGenerator : GroupProofGenerator {
    override suspend fun proveCreate(input: GroupProofCreateInput): GroupCreateProof {
        if (input.groupType != SepGroupType.TYRANNY) {
            throw GroupProofGeneratorError.NotYetSupported(input.groupType)
        }
        return GroupCreateProof(
            proof = ByteArray(1568) { 0xAB.toByte() },
            publicInputs = SepPublicInputs(
                commitment = ByteArray(32) { 0xCD.toByte() },
                epoch = 0uL,
            ),
        )
    }
}
