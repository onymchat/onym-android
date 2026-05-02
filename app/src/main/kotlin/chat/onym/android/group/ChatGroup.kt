package chat.onym.android.group

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import chat.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In-memory snapshot of a chat group as the Android app understands
 * it. PR-A holds this purely as a value type — `GroupRepository` and
 * the Room `@Entity` land in PR-B.
 *
 * Trimmed compared to `stellar-mls/clients/android` group entities:
 * the chat-message / avatar / push fields are out of scope until the
 * chat screen ships. [groupSecret] stays in the type because it's
 * seeded into the invitation envelope at create time and the receiver
 * needs it to derive message keys later.
 *
 * Mirrors `ChatGroup.swift` from onym-ios PR #24.
 */
@Serializable
data class ChatGroup(
    /** Hex-encoded 32-byte group ID. */
    val id: String,
    val name: String,
    /**
     * 32-byte shared secret. Used for `topicTag` derivation and
     * message-key HKDF (both still TBD on Android, but the value
     * MUST be sealed into the invitation now so receivers can rebuild
     * the same key).
     */
    @SerialName("group_secret")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupSecret: ByteArray,
    /** Unix epoch milliseconds (matches what `System.currentTimeMillis()`
     *  emits — one less unit conversion at persistence boundaries). */
    @SerialName("created_at")
    val createdAtMillis: Long,

    val members: List<GovernanceMember>,
    val epoch: ULong,
    @Serializable(with = Base64ByteArraySerializer::class)
    val salt: ByteArray,
    /**
     * Latest verified Poseidon commitment. `null` until the first
     * `recomputeCommitment` call (or until on-chain state is read
     * back).
     */
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray? = null,
    val tier: SepTier,
    @SerialName("group_type")
    val groupType: SepGroupType,
    /**
     * Hex (lowercase, 96 chars) BLS pubkey of the single Tyranny
     * admin. `null` for [SepGroupType.ANARCHY] / [SepGroupType.ONE_ON_ONE]
     * (no privileged member).
     */
    @SerialName("admin_pubkey_hex")
    val adminPubkeyHex: String? = null,
    /**
     * Flips to `true` once the relayer's `create_group_v2` returns
     * `accepted = true`. Persisted-but-not-anchored groups can be
     * retried.
     */
    @SerialName("is_published_on_chain")
    val isPublishedOnChain: Boolean = false,
) {
    /** Group ID as the raw 32-byte payload, parsed back from [id].
     *  Used directly when building chain payloads + invitations. */
    val groupIdBytes: ByteArray
        get() = bytesFromHex(id)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatGroup) return false
        return id == other.id &&
            name == other.name &&
            groupSecret.contentEquals(other.groupSecret) &&
            createdAtMillis == other.createdAtMillis &&
            members == other.members &&
            epoch == other.epoch &&
            salt.contentEquals(other.salt) &&
            (commitment?.contentEquals(other.commitment) ?: (other.commitment == null)) &&
            tier == other.tier &&
            groupType == other.groupType &&
            adminPubkeyHex == other.adminPubkeyHex &&
            isPublishedOnChain == other.isPublishedOnChain
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + groupSecret.contentHashCode()
        h = 31 * h + createdAtMillis.hashCode()
        h = 31 * h + members.hashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + (commitment?.contentHashCode() ?: 0)
        h = 31 * h + tier.hashCode()
        h = 31 * h + groupType.hashCode()
        h = 31 * h + (adminPubkeyHex?.hashCode() ?: 0)
        h = 31 * h + isPublishedOnChain.hashCode()
        return h
    }

    companion object {
        /** Lenient hex → bytes. Lowercases the input first so
         *  callers don't have to. Skips trailing odd nibbles
         *  silently — the constructor accepts only well-formed hex
         *  IDs in practice; this lenience exists to mirror iOS's
         *  `bytes(fromHex:)` parser. */
        fun bytesFromHex(hex: String): ByteArray {
            val lower = hex.lowercase()
            val out = ByteArray(lower.length / 2)
            for (i in out.indices) {
                val high = Character.digit(lower[i * 2], 16)
                val low = Character.digit(lower[i * 2 + 1], 16)
                if (high == -1 || low == -1) continue
                out[i] = ((high shl 4) or low).toByte()
            }
            return out
        }
    }
}
