package app.onym.android.group

import app.onym.android.foundation.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext payload that the admin seals (via
 * `IdentityRepository.sealInvitation`, X25519 + AES-GCM, signed by
 * the admin's Ed25519 stellar key) and ships to every existing
 * member's inbox after they Approve a join request. Tells receivers
 * "this person just joined the group — append them to your local
 * roster".
 *
 * Sits alongside [GroupInvitationPayload]:
 *   - [GroupInvitationPayload] is the joiner's first taste of a
 *     group — full state needed to render messages.
 *   - [MemberAnnouncementPayload] is incremental — existing members
 *     already know the group, they just need to learn about one new
 *     entry in the roster.
 *
 * ## Trust
 *
 * `newMember.alias` and [adminAlias] are self-asserted. Receivers
 * that care about provenance should display the BLS-pubkey
 * fingerprint alongside (matches the inviter-approval guidance on
 * [JoinRequestPayload]). The OUTER `SealedEnvelope` carries the
 * admin's Ed25519 signature over the ephemeral key — receivers MUST
 * cross-check `senderEd25519PublicKey` against the group's stored
 * `adminEd25519PubkeyHex` (PR 84) before mutating local state. That
 * signature check lives in the dispatcher, not here — this type is
 * a pure value carrier.
 *
 * ## Versioning
 *
 * `version = 1` is the only shape receivers handle today. Future
 * fields land via nullable defaults so older builds round-trip
 * unknown announcements as best-effort.
 *
 * ## Cross-platform parity
 *
 * Wire format authored on iOS first; this Kotlin twin mirrors the
 * snake_case keys + base64 [ByteArray] encoding (Swift `JSONEncoder`'s
 * `.base64` default + Kotlin's `Base64.getEncoder()` produce the
 * same bytes).
 *
 * Mirrors `MemberAnnouncementPayload.swift` from onym-ios PR #77.
 */
@Serializable
data class MemberAnnouncementPayload(
    val version: Int,
    /** 32-byte group ID — receivers cross-check against their
     *  local [ChatGroup.groupIdBytes] to refuse announcements for
     *  groups they don't know about. */
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @SerialName("new_member")
    val newMember: AnnouncedMember,
    /** Admin's user-visible alias at send time. Carried alongside
     *  the announcement so the receiver can render "Y admitted X"
     *  without needing a prior alias map keyed by admin pubkey. */
    @SerialName("admin_alias")
    val adminAlias: String,
    /**
     * 32-byte Poseidon commitment of the new tree (post-admit).
     * PR 88 (admin side) fills this; PR 89 (receiver side) verifies
     * it against the on-chain commitment. Optional on the wire —
     * pre-PR-88 senders ship without it. Receivers running PR 89+
     * MUST reject Tyranny announcements missing this field.
     */
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray? = null,
    /** New epoch number after the on-chain `update_commitment`
     *  (i.e. `epoch_old + 1`). Optional for the same reason as
     *  [commitment]. */
    val epoch: ULong? = null,
) {
    /**
     * One member's directory entry. App-level only — the
     * cryptographic Poseidon leaf hash is intentionally absent.
     * V1 group rosters are static on-chain (the joiner is not yet
     * a member of the Merkle tree), so the leaf hash is meaningless
     * to ship today. When on-chain joiner ceremonies land, a
     * `leaf_hash` field can return via a nullable default without
     * breaking older receivers.
     *
     * [blsPub] is still carried as the **stable cross-device
     * identifier**: HKDF-derived from the joiner's identity secret,
     * persisted across recovery-phrase restores, and forms the
     * dedup key in [ChatGroup.memberProfiles].
     */
    @Serializable
    data class AnnouncedMember(
        @SerialName("bls_pub")
        @Serializable(with = Base64ByteArraySerializer::class)
        val blsPub: ByteArray,
        @SerialName("inbox_pub")
        @Serializable(with = Base64ByteArraySerializer::class)
        val inboxPub: ByteArray,
        val alias: String,
        /**
         * 32-byte Ed25519 envelope-signing pubkey. Same key the
         * sealed-envelope signature is verified against. Required
         * on the wire so PR A4's chat dispatcher can verify any
         * subsequent chat message from this member with one direct
         * equality check.
         */
        @SerialName("sending_pub")
        @Serializable(with = Base64ByteArraySerializer::class)
        val sendingPub: ByteArray,
        /**
         * This member's agreement to the group's rules: the 32-byte
         * hash of the text they signed, and the 64-byte Ed25519
         * signature over [GroupRules.statement].
         *
         * Announced, and not merely kept by the founder who admitted
         * them, because the whole point of signing with [sendingPub] is
         * that every member already holds the key to check it. Left out
         * of the announcement, existing members would learn nothing
         * about anyone admitted after them, and "any member can verify"
         * would be true only of the admitting device.
         *
         * Null for a member admitted before rules existed, or by a
         * build that predates them.
         */
        @SerialName("rules_hash")
        @Serializable(with = Base64ByteArraySerializer::class)
        val rulesHash: ByteArray? = null,
        @SerialName("rules_signature")
        @Serializable(with = Base64ByteArraySerializer::class)
        val rulesSignature: ByteArray? = null,
        /**
         * The admitting device's copy of the rules those bytes cover.
         * Announced with them, because a hash with no text tells the
         * recipient that something was agreed and never what — and
         * unlike the founder, they have no other copy of the wording
         * that was current when this member joined.
         */
        @SerialName("rules_text")
        val rulesText: String? = null,
    ) {
        init {
            require(blsPub.size == 48) {
                "blsPub: expected 48 bytes, got ${blsPub.size}"
            }
            require(inboxPub.size == 32) {
                "inboxPub: expected 32 bytes, got ${inboxPub.size}"
            }
            require(sendingPub.size == 32) {
                "sendingPub: expected 32 bytes, got ${sendingPub.size}"
            }
            // Wrong-sized or half-present agreement bytes are rejected
            // rather than trimmed to null: an announcement is built by
            // this device and decoded from a peer's, and in both
            // directions "we can't show they agreed" should be a shape
            // nobody can accidentally produce.
            require((rulesHash == null) == (rulesSignature == null)) {
                "rulesHash and rulesSignature must be absent or present together"
            }
            require(rulesHash == null || rulesHash.size == 32) {
                "rulesHash: expected 32 bytes, got ${rulesHash?.size}"
            }
            require(rulesSignature == null || rulesSignature.size == 64) {
                "rulesSignature: expected 64 bytes, got ${rulesSignature?.size}"
            }
            // Capped like the link that carried it: this arrives from a
            // peer, and an uncapped string is a way to grow every
            // recipient's stored group without bound.
            // Measured as *sent*, not canonicalized first: `fits`
            // trims before measuring, so leading whitespace in front of
            // the real rules would be unbounded — and the hash check
            // downstream trims too, so it would pass as well.
            require(
                rulesText == null ||
                    rulesText.toByteArray(Charsets.UTF_8).size <= GroupRules.MAX_BYTES,
            ) {
                "rulesText: expected at most ${GroupRules.MAX_BYTES} UTF-8 bytes as sent"
            }
            // Deliberately *not* required to hash to [rulesHash]. The
            // sender stores its own copy of the rules beside whatever
            // the joiner signed, and iOS announces it whatever the
            // verdict was — so a mismatch is an ordinary
            // "signed-something-else" case, not a malformed
            // announcement. Rejecting it here would drop the whole
            // announcement and cost this device a member's inbox and
            // verification keys over a field that only adds evidence.
            // The receiver keeps the text only when it matches; see
            // `IncomingMessageDispatcher`.
        }

        override fun equals(other: Any?): Boolean = this === other ||
            (other is AnnouncedMember &&
                blsPub.contentEquals(other.blsPub) &&
                inboxPub.contentEquals(other.inboxPub) &&
                alias == other.alias &&
                sendingPub.contentEquals(other.sendingPub) &&
                (rulesHash?.contentEquals(other.rulesHash) ?: (other.rulesHash == null)) &&
                (rulesSignature?.contentEquals(other.rulesSignature)
                    ?: (other.rulesSignature == null)) &&
                rulesText == other.rulesText)

        override fun hashCode(): Int {
            var h = blsPub.contentHashCode()
            h = 31 * h + inboxPub.contentHashCode()
            h = 31 * h + alias.hashCode()
            h = 31 * h + sendingPub.contentHashCode()
            h = 31 * h + (rulesHash?.contentHashCode() ?: 0)
            h = 31 * h + (rulesSignature?.contentHashCode() ?: 0)
            h = 31 * h + (rulesText?.hashCode() ?: 0)
            return h
        }
    }

    init {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
        commitment?.let {
            require(it.size == 32) {
                "commitment: expected 32 bytes, got ${it.size}"
            }
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is MemberAnnouncementPayload &&
            version == other.version &&
            groupId.contentEquals(other.groupId) &&
            newMember == other.newMember &&
            adminAlias == other.adminAlias &&
            (commitment?.contentEquals(other.commitment) ?: (other.commitment == null)) &&
            epoch == other.epoch)

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + newMember.hashCode()
        h = 31 * h + adminAlias.hashCode()
        h = 31 * h + (commitment?.contentHashCode() ?: 0)
        h = 31 * h + (epoch?.hashCode() ?: 0)
        return h
    }
}
