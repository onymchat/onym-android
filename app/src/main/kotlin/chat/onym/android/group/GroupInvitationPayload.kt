package chat.onym.android.group

import chat.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext payload that gets sealed (X25519 + AES-GCM via
 * `IdentityRepository.sealInvitation`) and dropped on
 * `InboxTransport.send` for each invitee. Mirrors stellar-mls's
 * `InviteCode` shape so an Android / Apple receiver decoding the
 * payload sees the same fields.
 *
 * Versioned for forward compatibility — `version = 1` is the only
 * shape the receiver has to handle today; later versions can add
 * fields with optional defaults.
 *
 * `ByteArray` fields encode as base64 strings via
 * [Base64ByteArraySerializer] (matches iOS `Data` Codable shape; the
 * receiver round-trips losslessly on either platform).
 *
 * Mirrors `GroupInvitationPayload.swift` from onym-ios PR #26.
 */
@Serializable
data class GroupInvitationPayload(
    val version: Int,
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @SerialName("group_secret")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupSecret: ByteArray,
    val name: String,
    /** Lex-sorted by `publicKeyCompressed` — the receiver MUST be
     *  able to recompute the same Poseidon root, which requires the
     *  same canonical order. */
    val members: List<GovernanceMember>,
    val epoch: ULong,
    @Serializable(with = Base64ByteArraySerializer::class)
    val salt: ByteArray,
    /** Latest verified Poseidon commitment. `null` only for offline
     *  invitations not yet anchored on chain — current creator flow
     *  always anchors first, so this is always set in practice. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray? = null,
    @SerialName("tier_raw")
    val tierRaw: Int,
    /** Lowercase governance label — `tyranny`, `anarchy`, etc.
     *  Receiver decodes via [chat.onym.android.chain.SepGroupType.fromWire].
     *  String spelling matches the relayer + contract wire format so a
     *  single `group_type_raw` field is unambiguous across iOS, Android,
     *  and the Stellar contract. Type changed from `UInt` → `String` in
     *  the PR-C follow-up to mirror onym-ios PR #27. */
    @SerialName("group_type_raw")
    val groupTypeRaw: String,
    /** Lowercase hex (96 chars) BLS pubkey of the Tyranny admin.
     *  `null` for ANARCHY / ONE_ON_ONE. */
    @SerialName("admin_pubkey_hex")
    val adminPubkeyHex: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupInvitationPayload) return false
        return version == other.version &&
            groupId.contentEquals(other.groupId) &&
            groupSecret.contentEquals(other.groupSecret) &&
            name == other.name &&
            members == other.members &&
            epoch == other.epoch &&
            salt.contentEquals(other.salt) &&
            (commitment?.contentEquals(other.commitment) ?: (other.commitment == null)) &&
            tierRaw == other.tierRaw &&
            groupTypeRaw == other.groupTypeRaw &&
            adminPubkeyHex == other.adminPubkeyHex
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + groupSecret.contentHashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + members.hashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + (commitment?.contentHashCode() ?: 0)
        h = 31 * h + tierRaw
        h = 31 * h + groupTypeRaw.hashCode()
        h = 31 * h + (adminPubkeyHex?.hashCode() ?: 0)
        return h
    }
}
