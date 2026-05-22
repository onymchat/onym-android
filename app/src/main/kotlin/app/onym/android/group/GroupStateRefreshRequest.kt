package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Member → admin "send me the current group state" request, sealed to
 * the admin's inbox and signed by the requester's Ed25519.
 *
 * ## Why this exists
 *
 * A Tyranny invitation is a snapshot pinned to one epoch. The receiver
 * verifies it by recomputing `Poseidon(Poseidon(merkleRoot(members),
 * epoch), salt)` and matching the on-chain commitment — but the chain
 * only stores the *latest* `(commitment, epoch)`. If another invitee
 * was anchored between the snapshot being sealed and it landing, the
 * snapshot is from a past epoch the chain no longer holds, so it can't
 * be byte-verified (and we refuse to materialize an unverifiable group
 * — see [app.onym.android.inbox.GroupStateVerifier]).
 *
 * To recover, the receiver asks the admin for the *current* state. The
 * admin replies with a fresh [GroupInvitationPayload] at the current
 * `(epoch, salt, members, commitment)`, which the receiver verifies at
 * an exact epoch match. This is the "verify at current state" leg of
 * the converge-forward design (Option 2).
 *
 * ## Privacy
 *
 * The reply carries `salt` (the on-chain roster-privacy blinding
 * factor), so the admin MUST only answer requesters that are in the
 * *current* roster — [app.onym.android.inbox.GroupStateVerifier.handleRefreshRequest]
 * gates on membership + an Ed25519-signer check before replying, and
 * seals to the member's *stored* inbox so a forged request can't
 * redirect the salt. The request itself reveals only that the
 * requester wants to resync a group it was invited to.
 *
 * ## Wire disambiguation
 *
 * [groupId] (`refresh_group_id`) + [requesterInboxPublicKey]
 * (`requester_inbox_pub`) are required and unique to this type — no
 * other inbox payload carries `refresh_group_id`, so a successful
 * decode is unambiguous.
 *
 * Mirrors `GroupStateRefreshRequest` from onym-ios PR #159.
 */
@Serializable
data class GroupStateRefreshRequest(
    @SerialName("refresh_version")
    val version: Int = 1,

    /** 32-byte on-chain `group_id` the requester wants the current
     *  state for. */
    @SerialName("refresh_group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,

    /** Requester's 32-byte X25519 inbox key — where the admin seals the
     *  fresh snapshot reply (cross-checked against the stored member
     *  profile's inbox, never trusted blindly). */
    @SerialName("requester_inbox_pub")
    @Serializable(with = Base64ByteArraySerializer::class)
    val requesterInboxPublicKey: ByteArray,

    /** Requester's 48-byte compressed BLS pubkey — used by the admin to
     *  confirm the requester is in the current roster before disclosing
     *  the salt. */
    @SerialName("requester_bls_pub")
    @Serializable(with = Base64ByteArraySerializer::class)
    val requesterBlsPublicKey: ByteArray,
) {
    init {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
        require(requesterInboxPublicKey.size == 32) {
            "requesterInboxPublicKey: expected 32 bytes, got ${requesterInboxPublicKey.size}"
        }
        require(requesterBlsPublicKey.size == 48) {
            "requesterBlsPublicKey: expected 48 bytes, got ${requesterBlsPublicKey.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupStateRefreshRequest) return false
        return version == other.version &&
            groupId.contentEquals(other.groupId) &&
            requesterInboxPublicKey.contentEquals(other.requesterInboxPublicKey) &&
            requesterBlsPublicKey.contentEquals(other.requesterBlsPublicKey)
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + requesterInboxPublicKey.contentHashCode()
        h = 31 * h + requesterBlsPublicKey.contentHashCode()
        return h
    }
}
