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
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JoinRequestPayload) return false
        return joinerInboxPublicKey.contentEquals(other.joinerInboxPublicKey) &&
            joinerDisplayLabel == other.joinerDisplayLabel &&
            groupId.contentEquals(other.groupId)
    }

    override fun hashCode(): Int {
        var h = joinerInboxPublicKey.contentHashCode()
        h = 31 * h + joinerDisplayLabel.hashCode()
        h = 31 * h + groupId.contentHashCode()
        return h
    }
}
