package chat.onym.android.group

import chat.onym.android.identity.Base64ByteArraySerializer
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
    ) {
        init {
            require(blsPub.size == 48) {
                "blsPub: expected 48 bytes, got ${blsPub.size}"
            }
            require(inboxPub.size == 32) {
                "inboxPub: expected 32 bytes, got ${inboxPub.size}"
            }
        }

        override fun equals(other: Any?): Boolean = this === other ||
            (other is AnnouncedMember &&
                blsPub.contentEquals(other.blsPub) &&
                inboxPub.contentEquals(other.inboxPub) &&
                alias == other.alias)

        override fun hashCode(): Int {
            var h = blsPub.contentHashCode()
            h = 31 * h + inboxPub.contentHashCode()
            h = 31 * h + alias.hashCode()
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
