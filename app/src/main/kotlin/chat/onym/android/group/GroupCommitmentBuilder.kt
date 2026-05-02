package chat.onym.android.group

import chat.onym.android.chain.SepTier
import chat.onym.sdk.Common
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Thin wrapper around [chat.onym.sdk.Common] that speaks
 * [GovernanceMember] rather than raw byte buffers. Mirrors
 * `SEPCommitmentBuilder` in `swift-mls` (and
 * `GroupCommitmentBuilder.swift` from onym-ios PR #24) so
 * cross-platform behaviour (and test vectors) stays aligned.
 *
 * All FFI calls are synchronous — the heavy work is hashing, not
 * proving, so they're fine on the calling thread.
 */
object GroupCommitmentBuilder {

    /**
     * Fresh 32-byte salt for a brand-new group. The circuit
     * interprets the bytes little-endian-mod-r in-circuit.
     */
    fun generateSalt(): ByteArray =
        ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * Deterministic salt rotation for member-add events.
     * `SHA256(previousSalt || memberKey)` — both sides of a
     * member-join derive the same salt and therefore the same
     * epoch's encryption key, so observers don't fork.
     */
    fun deriveSalt(previousSalt: ByteArray, memberKey: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(previousSalt)
        md.update(memberKey)
        return md.digest()
    }

    /** 32-byte Poseidon leaf hash for a single member's BLS Fr secret. */
    fun computeLeafHash(secretKey: ByteArray): ByteArray =
        Common.leafHash(secretKey)

    /** 48-byte arkworks-compressed G1 BLS public key for a 32-byte
     *  secret. Used to compute the stable [GovernanceMember.publicKeyCompressed]. */
    fun computePublicKey(secretKey: ByteArray): ByteArray =
        Common.publicKey(secretKey)

    /**
     * Sort [members] lex by [GovernanceMember.publicKeyCompressed],
     * pack the [GovernanceMember.leafHash] bytes, and ask OnymSDK for
     * the Poseidon Merkle root at the supplied [tier]'s depth.
     *
     * The lex sort matches SEP-XXXX §2.1 — both peers MUST sort
     * identically before computing roots, otherwise commitments
     * diverge.
     */
    fun computeMerkleRoot(members: List<GovernanceMember>, tier: SepTier): ByteArray {
        val sorted = members.sortedWith { a, b -> compareLex(a.publicKeyCompressed, b.publicKeyCompressed) }
        val packed = ByteArray(sorted.size * 32)
        for ((i, member) in sorted.withIndex()) {
            require(member.leafHash.size == 32) {
                "leafHash for member $i has unexpected size ${member.leafHash.size}, expected 32"
            }
            System.arraycopy(member.leafHash, 0, packed, i * 32, 32)
        }
        return Common.merkleRoot(packed, tier.depth)
    }

    /**
     * `Poseidon(Poseidon(root, Fr(epoch)), salt_fr)`. The plonk-era
     * commitment shape used by every sep-* contract.
     */
    fun computePoseidonCommitment(
        poseidonRoot: ByteArray,
        epoch: ULong,
        salt: ByteArray,
    ): ByteArray = Common.poseidonCommitment(poseidonRoot, epoch.toLong(), salt)

    /** Lex byte comparison, returning negative / zero / positive. */
    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }
}
