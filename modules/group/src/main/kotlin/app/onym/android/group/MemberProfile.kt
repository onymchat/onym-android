package app.onym.android.group

import app.onym.android.foundation.Base64ByteArraySerializer
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
    /**
     * 32-byte `SHA256` of the rules this member agreed to when they
     * asked to join, and their 64-byte Ed25519 signature over
     * [GroupRules.statement]. Both null for a member who joined before
     * the group had rules, or from a build that predates them.
     *
     * Kept on the member rather than on the request, because the request
     * is consumed at approval and the question ("did they agree?")
     * outlives it by the whole life of the membership.
     *
     * Announced alongside the rest of the profile, so any member can
     * check any other member's agreement against the [sendingPubkey]
     * they already hold — the founder who admitted them is not a
     * required witness.
     */
    @SerialName("rules_hash")
    @Serializable(with = Base64ByteArraySerializer::class)
    val rulesHash: ByteArray? = null,
    @SerialName("rules_signature")
    @Serializable(with = Base64ByteArraySerializer::class)
    val rulesSignature: ByteArray? = null,
    /**
     * The rules text those bytes cover, as this device held it when the
     * member was admitted.
     *
     * Retained rather than pointed at. [GroupRules]' own doc is the
     * argument: a signature is evidence only if the bytes it covers can
     * be produced again, and a hash beside a *live*
     * [ChatGroup.invitationMessage] proves that something was agreed and
     * never what — one edit by the founder and every retained agreement
     * becomes unattributable to any text on the device.
     *
     * It is the admitting device's own copy, never the joiner's: the
     * request deliberately doesn't carry the text, because a joiner who
     * supplied it would be choosing what their own signature is checked
     * against.
     */
    @SerialName("rules_text")
    val rulesText: String? = null,
) {
    init {
        require(inboxPublicKey.size == 32) {
            "inboxPublicKey: expected 32 bytes, got ${inboxPublicKey.size}"
        }
        require(sendingPubkey.size == 32) {
            "sendingPubkey: expected 32 bytes, got ${sendingPubkey.size}"
        }
        // Same shape the wire enforces on [JoinRequestPayload], enforced
        // here too: profiles arrive inside peer-announced snapshots, and
        // a malformed pair should fail at the boundary rather than at
        // verify time, where "wrong size" would read as "didn't agree".
        require((rulesHash == null) == (rulesSignature == null)) {
            "rulesHash and rulesSignature must be absent or present together"
        }
        require(rulesHash == null || rulesHash.size == 32) {
            "rulesHash: expected 32 bytes, got ${rulesHash?.size}"
        }
        require(rulesSignature == null || rulesSignature.size == 64) {
            "rulesSignature: expected 64 bytes, got ${rulesSignature?.size}"
        }
    }

    /**
     * Whether this member's stored signature verifies against the text
     * stored beside it, for [groupId].
     *
     * The whole question in one place, and the only place that answers
     * it: the retained text is what the signature covers, so anything
     * reaching for the group's current rules instead would report a
     * founder's later edit as a member who never agreed.
     */
    fun agreedToRules(groupId: ByteArray): Boolean {
        val signature = rulesSignature ?: return false
        val text = rulesText ?: return false
        return GroupRules.isAgreement(
            signature = signature,
            rules = text,
            groupId = groupId,
            joinerSendingPublicKey = sendingPubkey,
        )
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is MemberProfile &&
            alias == other.alias &&
            inboxPublicKey.contentEquals(other.inboxPublicKey) &&
            sendingPubkey.contentEquals(other.sendingPubkey) &&
            // A profile that gained an agreement is not the profile
            // without it: left out, the `snapshots` flow would treat the
            // two as one value and never re-emit the group whose only
            // change is the evidence a founder decided on.
            (rulesHash?.contentEquals(other.rulesHash) ?: (other.rulesHash == null)) &&
            (rulesSignature?.contentEquals(other.rulesSignature)
                ?: (other.rulesSignature == null)) &&
            rulesText == other.rulesText)

    override fun hashCode(): Int {
        var h = alias.hashCode()
        h = 31 * h + inboxPublicKey.contentHashCode()
        h = 31 * h + sendingPubkey.contentHashCode()
        h = 31 * h + (rulesHash?.contentHashCode() ?: 0)
        h = 31 * h + (rulesSignature?.contentHashCode() ?: 0)
        h = 31 * h + (rulesText?.hashCode() ?: 0)
        return h
    }
}
