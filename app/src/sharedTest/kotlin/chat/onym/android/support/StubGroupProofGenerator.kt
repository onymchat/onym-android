package chat.onym.android.support

import chat.onym.android.chain.GroupCreateProof
import chat.onym.android.chain.GroupProofCreateInput
import chat.onym.android.chain.GroupProofGenerator
import chat.onym.android.chain.GroupProofGeneratorError
import chat.onym.android.chain.GroupProofUpdateInput
import chat.onym.android.chain.GroupUpdateProof
import chat.onym.android.chain.SepGroupType

/**
 * Returns a deterministic 1601-byte "proof" + a 4-chunk PI bundle
 * without actually proving. Skips the ~3.5s real prover so the
 * interactor test suite stays fast — the full real prove path is
 * exercised by `GroupProofGeneratorFfiTest` from PR-B.
 *
 * Mirrors `StubGroupProofGenerator` from onym-ios PR #27 (rewritten
 * in the follow-up to drop the parsed-1568 + epoch-pair shape).
 */
class StubGroupProofGenerator : GroupProofGenerator {
    override suspend fun proveCreate(input: GroupProofCreateInput): GroupCreateProof =
        when (input.groupType) {
            SepGroupType.TYRANNY -> GroupCreateProof(
                proof = ByteArray(1601) { 0xAB.toByte() },
                publicInputs = listOf(
                    ByteArray(32) { 0xCD.toByte() },  // commitment
                    ByteArray(32),                     // Fr(0)
                    ByteArray(32) { 0xEE.toByte() },  // admin_pubkey_commitment
                    ByteArray(32) { 0xFF.toByte() },  // group_id_fr
                ),
            )
            SepGroupType.ONE_ON_ONE -> {
                if (input.secondaryBlsSecretKey == null) {
                    throw GroupProofGeneratorError.SecondaryBlsSecretKeyRequired
                }
                GroupCreateProof(
                    proof = ByteArray(1601) { 0x12.toByte() },
                    publicInputs = listOf(
                        ByteArray(32) { 0x34.toByte() },  // commitment
                        ByteArray(32),                     // Fr(0)
                    ),
                )
            }
            SepGroupType.ANARCHY -> GroupCreateProof(
                proof = ByteArray(1601) { 0x56.toByte() },
                publicInputs = listOf(
                    ByteArray(32) { 0x78.toByte() },  // commitment
                    ByteArray(32),                     // Fr(0) — epoch
                ),
            )
            else -> throw GroupProofGeneratorError.NotYetSupported(input.groupType)
        }

    override suspend fun proveUpdate(input: GroupProofUpdateInput): GroupUpdateProof =
        when (input.groupType) {
            SepGroupType.TYRANNY -> GroupUpdateProof(
                proof = ByteArray(1601) { 0x9A.toByte() },
                publicInputs = listOf(
                    ByteArray(32) { 0xC0.toByte() },  // c_old
                    java.nio.ByteBuffer.allocate(32).apply {
                        position(24)
                        putLong(input.epochOld.toLong())
                    }.array(),                          // epoch_old_be
                    ByteArray(32) { 0xC1.toByte() },  // c_new
                    ByteArray(32) { 0xAD.toByte() },  // admin_pubkey_commitment
                    ByteArray(32) { 0x10.toByte() },  // group_id_fr
                ),
            )
            else -> throw GroupProofGeneratorError.NotYetSupported(input.groupType)
        }
}
