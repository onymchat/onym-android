package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Admin → invitee "you've been invited" offer, sealed to the
 * invitee's X25519 inbox key and delivered over the normal inbox
 * transport (NOT an intro tag — the invitee doesn't have one yet).
 *
 * ## Why this is not a [GroupInvitationPayload]
 *
 * A [GroupInvitationPayload] is a *membership grant*: it pins
 * `(members, epoch, commitment, salt)` for one on-chain epoch, and
 * the receiver auto-materializes a group from it. Shipping that at
 * create time is wrong on two counts:
 *   1. The invitee isn't on chain yet — they have no BLS leaf in the
 *      committed roster, so the snapshot is a lie.
 *   2. It's epoch-pinned, so the moment any *other* invitee is
 *      anchored the snapshot goes stale and the receiver drops it.
 *
 * The offer instead carries only what the invitee needs to *ask* to
 * join: a fresh per-invite intro public key (the reply channel the
 * admin minted via [InviteIntroducer]) plus enough context to render
 * "Alice invited you to Maple Garden". It contains **no** epoch,
 * commitment, members, or group secret — so it never expires and
 * grants nothing. Membership only happens later, when the invitee
 * explicitly accepts (ships a [JoinRequestPayload] to [introPublicKey])
 * and the admin explicitly approves (anchors via `update_commitment`).
 *
 * ## Wire disambiguation
 *
 * The receive-side dispatcher trial-decodes inbound plaintext against
 * several payload types. [version] (`offer_version`) + [inviterAlias]
 * (`inviter_alias`) + [introPublicKey] (`intro_pub`) are all required
 * (no defaults) and unique to this type, so a successful decode is
 * unambiguous — no other inbox payload carries `inviter_alias`, and a
 * [JoinRequestPayload] (the other intro-keyed payload) lacks
 * `intro_pub` + `offer_version` so it can't be mistaken for an offer.
 *
 * Mirrors `GroupInviteOfferPayload` from onym-ios PR #158.
 */
@Serializable
data class GroupInviteOfferPayload(
    @SerialName("offer_version")
    val version: Int = 1,

    /** 32-byte X25519 public key of the admin's freshly-minted intro
     *  key for this invite. The invitee seals their [JoinRequestPayload]
     *  to this and the admin's [IntroInboxPump] picks it up. */
    @SerialName("intro_pub")
    @Serializable(with = Base64ByteArraySerializer::class)
    val introPublicKey: ByteArray,

    /** 32-byte on-chain `group_id` the invite is for. Lets the invitee
     *  verify the group exists on chain before responding. */
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,

    /** Optional plaintext group name for the invitee's preview.
     *  Mirrors [IntroCapability.groupName] — treat as low-sensitivity
     *  (it's sealed here, but it transits cleartext-ish channels in
     *  the deeplink form). */
    @SerialName("group_name")
    val groupName: String? = null,

    /** Admin's self-asserted display name, surfaced in the invitee's
     *  "X invited you" prompt. Untrusted text — render, don't trust. */
    @SerialName("inviter_alias")
    val inviterAlias: String,
) {
    init {
        require(introPublicKey.size == 32) {
            "introPublicKey: expected 32 bytes, got ${introPublicKey.size}"
        }
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    /**
     * Rebuild the [IntroCapability] the invitee feeds to
     * [JoinRequestSender.send] on accept. Throws if the offer's bytes
     * don't satisfy [IntroCapability]'s shape invariants (can't happen
     * for an offer that decoded successfully, but [IntroCapability]'s
     * `init` is the single source of truth for the sizes).
     */
    fun introCapability(): IntroCapability =
        IntroCapability(
            introPublicKey = introPublicKey,
            groupId = groupId,
            groupName = groupName,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupInviteOfferPayload) return false
        return version == other.version &&
            introPublicKey.contentEquals(other.introPublicKey) &&
            groupId.contentEquals(other.groupId) &&
            groupName == other.groupName &&
            inviterAlias == other.inviterAlias
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + introPublicKey.contentHashCode()
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + (groupName?.hashCode() ?: 0)
        h = 31 * h + inviterAlias.hashCode()
        return h
    }
}
