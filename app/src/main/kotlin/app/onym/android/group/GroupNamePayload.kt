package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Dedicated "group name" control message: the admin broadcasting a
 * renamed group to every member, out of band from the chat timeline.
 * Sealed (X25519 + AES-GCM via `IdentityRepository.sealInvitation`) and
 * shipped on the same per-inbox envelope path as [GroupAvatarPayload] —
 * one envelope per member inbox.
 *
 * The `name_*` wire keys are deliberately distinct (mirroring
 * [GroupAvatarPayload]'s `avatar_*`) so the dispatcher's structural JSON
 * trial-decode can't confuse it with any other payload.
 *
 * [senderBlsHex] is informational only — do **not** authenticate with
 * it. Provenance is the OUTER `SealedEnvelope` Ed25519 signature,
 * cross-checked by the dispatcher against the group's stored
 * `adminEd25519PubkeyHex` (same rule as [GroupAvatarPayload]). The name
 * is app metadata only — it is not part of the cryptographic group
 * state and never touches on-chain data.
 *
 * Mirrors `GroupNamePayload.swift` from onym-ios.
 */
@Serializable
data class GroupNamePayload(
    @SerialName("name_version")
    val version: Int,
    @SerialName("name_group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    /** Lowercase BLS pubkey hex (96 chars) of the admin who renamed the
     *  group. Informational only — never used to authenticate. */
    @SerialName("name_sender_bls_hex")
    val senderBlsHex: String,
    /** Milliseconds since the Unix epoch at send time. */
    @SerialName("name_sent_at_millis")
    val sentAtMillis: Long,
    /** The new group name. Untrusted text — render, don't trust. */
    @SerialName("name_value")
    val name: String,
) {
    init {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is GroupNamePayload &&
            version == other.version &&
            groupId.contentEquals(other.groupId) &&
            senderBlsHex == other.senderBlsHex &&
            sentAtMillis == other.sentAtMillis &&
            name == other.name)

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + senderBlsHex.hashCode()
        h = 31 * h + sentAtMillis.hashCode()
        h = 31 * h + name.hashCode()
        return h
    }
}
