package chat.onym.android.group

import chat.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One member-leaf entry in a group's roster. The two byte arrays are
 * derived from the same 32-byte BLS Fr secret:
 *
 *  - [publicKeyCompressed] — 48 bytes, arkworks-compressed G1Affine
 *    (`[sk] · G`). Used for Merkle-tree leaf ordering (lex sort) and
 *    as the stable on-the-wire identifier of a member across epochs.
 *  - [leafHash] — 32 bytes, `Poseidon(sk_fr)`. The actual scalar that
 *    lands in the Poseidon Merkle tree the contract verifies against.
 *
 * Both fields encode as base64 strings (matches the iOS Codable
 * shape; cross-platform invitation payloads round-trip without
 * platform-specific shims).
 *
 * Mirrors `GovernanceMember.swift` from onym-ios PR #24 and
 * `SEPGroupMemberLeaf` in `swift-mls`.
 */
@Serializable
data class GovernanceMember(
    @SerialName("public_key_compressed")
    @Serializable(with = Base64ByteArraySerializer::class)
    val publicKeyCompressed: ByteArray,
    @SerialName("leaf_hash")
    @Serializable(with = Base64ByteArraySerializer::class)
    val leafHash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GovernanceMember) return false
        return publicKeyCompressed.contentEquals(other.publicKeyCompressed) &&
            leafHash.contentEquals(other.leafHash)
    }

    override fun hashCode(): Int =
        31 * publicKeyCompressed.contentHashCode() + leafHash.contentHashCode()
}
