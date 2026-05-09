package chat.onym.android.group

import chat.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inner plaintext of the sealed envelope a joiner sends to an
 * inviter's intro inbox. Carries everything the inviter's app
 * needs to ship the actual sealed [GroupInvitationPayload] back:
 *
 *  - [joinerInboxPublicKey] — where the sealed invitation will be
 *    posted (the joiner's identity inbox X25519 key).
 *  - [joinerDisplayLabel] — UI hint for the inviter's "X wants to
 *    join Y. Approve?" prompt. Joiner-controlled, **untrusted**;
 *    the inviter's app should also surface
 *    [joinerInboxPublicKey]'s hex prefix so the inviter can
 *    verify out-of-band when the label can't be cross-checked.
 *  - [groupId] — echoed back so the inviter's approval handler can
 *    cross-check that the joiner is asking about the right group.
 *
 * The OUTER envelope is the standard
 * [chat.onym.android.identity.SealedEnvelope] (X25519+AES-GCM
 * sealed to the inviter's intro pubkey + Ed25519-signed by the
 * joiner's identity key). Reuses the existing seal/decrypt
 * machinery on `IdentityRepository`.
 */
@Serializable
data class JoinRequestPayload(
    @SerialName("joiner_inbox_pub")
    @Serializable(with = Base64ByteArraySerializer::class)
    val joinerInboxPublicKey: ByteArray,
    /**
     * 48-byte arkworks-compressed BLS12-381 G1 pubkey. Optional —
     * pre-PR-78 joiners shipped requests without it. The admin uses
     * this as the **stable cross-device key** under which to record
     * the joiner in [ChatGroup.memberProfiles] (and, post-PR-88, as
     * the joiner's on-chain Merkle leaf input).
     *
     * Default null so older joiners' wire payloads still decode. The
     * approver short-circuits the local-roster mutation (and the
     * Tyranny anchor flow, post-PR-88) when this is missing.
     */
    @SerialName("joiner_bls_pub")
    @Serializable(with = Base64ByteArraySerializer::class)
    val joinerBlsPublicKey: ByteArray? = null,
    /**
     * 32-byte Poseidon leaf hash (PR 88). Required for the admin to
     * generate the on-chain `update_commitment` proof. `null` for
     * pre-PR-88 joiners — those requests can't be approved on-chain
     * and surface as `OutdatedJoinerClient`.
     */
    @SerialName("joiner_leaf_hash")
    @Serializable(with = Base64ByteArraySerializer::class)
    val joinerLeafHash: ByteArray? = null,
    @SerialName("joiner_display_label")
    val joinerDisplayLabel: String,
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
) {
    init {
        require(joinerInboxPublicKey.size == 32) {
            "joinerInboxPublicKey: expected 32 bytes, got ${joinerInboxPublicKey.size}"
        }
        joinerBlsPublicKey?.let {
            require(it.size == 48) {
                "joinerBlsPublicKey: expected 48 bytes, got ${it.size}"
            }
        }
        joinerLeafHash?.let {
            require(it.size == 32) {
                "joinerLeafHash: expected 32 bytes, got ${it.size}"
            }
        }
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JoinRequestPayload) return false
        return joinerInboxPublicKey.contentEquals(other.joinerInboxPublicKey) &&
            (joinerBlsPublicKey?.contentEquals(other.joinerBlsPublicKey)
                ?: (other.joinerBlsPublicKey == null)) &&
            (joinerLeafHash?.contentEquals(other.joinerLeafHash)
                ?: (other.joinerLeafHash == null)) &&
            joinerDisplayLabel == other.joinerDisplayLabel &&
            groupId.contentEquals(other.groupId)
    }

    override fun hashCode(): Int {
        var h = joinerInboxPublicKey.contentHashCode()
        h = 31 * h + (joinerBlsPublicKey?.contentHashCode() ?: 0)
        h = 31 * h + (joinerLeafHash?.contentHashCode() ?: 0)
        h = 31 * h + joinerDisplayLabel.hashCode()
        h = 31 * h + groupId.contentHashCode()
        return h
    }
}
