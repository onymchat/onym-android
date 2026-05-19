package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * View-facing directory entry for one peer the local user has
 * interacted with through a group. Carries what the UI needs to
 * render "X joined" / "you are talking to Y" without crossing into
 * secret material. Stored on [ChatGroup.memberProfiles] keyed by
 * the peer's lowercase BLS pubkey hex.
 *
 * Distinct from [GovernanceMember]: that's the on-chain Merkle-tree
 * roster (V1: creator only, static). [MemberProfile] covers the
 * app-level "who's in this conversation" set, which V1 grows as
 * joiners are admitted even though the on-chain roster doesn't
 * change.
 *
 * Trust: [alias] is self-asserted by its owner — never load-bearing.
 * Surfaces should always offer the member's BLS-pubkey fingerprint
 * alongside (matches the inviter-approval pattern documented on
 * [JoinRequestPayload]).
 *
 * [inboxPublicKey] is the 32-byte X25519 raw pub. Persisted so the
 * admin (or any authorized fanout sender, in future governance
 * models) can reach every member's inbox to announce roster changes
 * without re-deriving from the join request each time.
 *
 * Mirrors `MemberProfile.swift` from onym-ios.
 */
@Serializable
data class MemberProfile(
    val alias: String,
    @SerialName("inbox_public_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val inboxPublicKey: ByteArray,
    /**
     * 32-byte Ed25519 envelope-signing pubkey. Matches
     * [app.onym.android.identity.Identity.stellarPublicKey] — the
     * key the receiver verifies sealed envelopes against. Plumbed
     * end-to-end through the join + invite flows (PR A3) so PR A4's
     * chat dispatcher can match a chat envelope's signature against
     * the claimed [senderBlsPubkeyHex] member with one direct
     * equality check, closing the insider-spoofing gap a malicious
     * group member could otherwise exploit.
     */
    @SerialName("sending_pubkey")
    @Serializable(with = Base64ByteArraySerializer::class)
    val sendingPubkey: ByteArray,
) {
    init {
        require(inboxPublicKey.size == 32) {
            "inboxPublicKey: expected 32 bytes, got ${inboxPublicKey.size}"
        }
        require(sendingPubkey.size == 32) {
            "sendingPubkey: expected 32 bytes, got ${sendingPubkey.size}"
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is MemberProfile &&
            alias == other.alias &&
            inboxPublicKey.contentEquals(other.inboxPublicKey) &&
            sendingPubkey.contentEquals(other.sendingPubkey))

    override fun hashCode(): Int {
        var h = alias.hashCode()
        h = 31 * h + inboxPublicKey.contentHashCode()
        h = 31 * h + sendingPubkey.contentHashCode()
        return h
    }
}
