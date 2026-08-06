package app.onym.android.group

import app.onym.android.identity.IdentityId

/**
 * One per-invite ephemeral keypair persisted on the inviter's
 * device. Maps an invite link's [introPublicKey] (the public half
 * shipped in the [IntroCapability] inside the link) to its private
 * counterpart + the metadata needed to dispatch a sealed
 * [GroupInvitationPayload] when an approved request comes in.
 *
 * - [introPrivateKey] is X25519 secret material — used to decrypt
 *   the joiner's request envelope. Never logged. Per-invite, so
 *   leaking one doesn't compromise unrelated invites.
 * - [ownerIdentityId] scopes the entry to the identity that minted
 *   the link. The cascade-delete on identity removal (PR-3 of the
 *   multi-identity stack) wipes every entry whose owner gets
 *   removed.
 * - [groupId] is the on-chain `group_id` the invite is for —
 *   needed when the inviter's app surfaces "Bob wants to join
 *   <group>?" so it can render the group's name.
 * - [createdAtMillis] is display-only; links live until revoked.
 * - [label] is null for the shared link, else the invitee's fingerprint.
 */
data class IntroKeyEntry(
    val introPublicKey: ByteArray,
    val introPrivateKey: ByteArray,
    val ownerIdentityId: IdentityId,
    val groupId: ByteArray,
    val createdAtMillis: Long,
    /** null == the group's shared link. Non-null == a create-time
     *  offer aimed at one invitee. */
    val label: String? = null,
) {
    init {
        require(introPublicKey.size == 32) {
            "introPublicKey: expected 32 bytes, got ${introPublicKey.size}"
        }
        require(introPrivateKey.size == 32) {
            "introPrivateKey: expected 32 bytes, got ${introPrivateKey.size}"
        }
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    companion object {
        /** First 4 bytes of the inbox key — the same fingerprint
         *  shape the approval screen shows. */
        fun fingerprint(inboxPublicKey: ByteArray): String =
            inboxPublicKey.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntroKeyEntry) return false
        return introPublicKey.contentEquals(other.introPublicKey) &&
            introPrivateKey.contentEquals(other.introPrivateKey) &&
            ownerIdentityId == other.ownerIdentityId &&
            groupId.contentEquals(other.groupId) &&
            createdAtMillis == other.createdAtMillis
    }

    override fun hashCode(): Int {
        var h = introPublicKey.contentHashCode()
        h = 31 * h + introPrivateKey.contentHashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + createdAtMillis.hashCode()
        return h
    }
}
