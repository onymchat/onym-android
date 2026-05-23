package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Dedicated admin-signed control message announcing the group's
 * **current** avatar state. The admin seals it (X25519 + AES-GCM via
 * `IdentityRepository.sealInvitation`, signed by their Ed25519 stellar
 * key) and ships one envelope per member inbox — the same fan-out path
 * as [MemberAnnouncementPayload]. Carries later avatar changes;
 * create-time members get their first photo from
 * [GroupInvitationPayload.avatar] instead.
 *
 * ## Wire shape
 *
 * The keys are deliberately prefixed `avatar_*` so the payload can't be
 * structurally mistaken for a [app.onym.android.chats.ChatMessagePayload]
 * (which shares `version` / `group_id` / `sender` / `timestamp`
 * spellings). The receive-side dispatcher trial-decodes this type
 * **before** the chat-message branch; the unique keys keep the two from
 * colliding in either direction.
 *
 * ```json
 * {
 *   "avatar_version": 1,
 *   "avatar_group_id": "<base64 of 32-byte group id>",
 *   "avatar_sender_bls_hex": "<lowercase BLS pubkey hex, 96 chars>",
 *   "avatar_sent_at_millis": 1700000000000,
 *   "avatar": "<base64 JPEG, OR omit the key to signal removal>"
 * }
 * ```
 *
 * ## Removal semantics
 *
 * This message always expresses the *current* avatar state, so an
 * absent / nil [avatar] means the photo was **cleared** — not
 * "unspecified". Senders omit the key on removal; receivers clear the
 * stored avatar when it's absent.
 *
 * ## Trust
 *
 * [senderBlsHex] is informational only (for display / debugging) — do
 * **not** authenticate with it. Provenance is the OUTER `SealedEnvelope`
 * Ed25519 signature, cross-checked by the dispatcher against the
 * group's stored `adminEd25519PubkeyHex` (same rule as
 * [MemberAnnouncementPayload]). There is no on-chain commitment check —
 * the avatar isn't part of the cryptographic group state.
 *
 * Mirrors `GroupAvatarPayload.swift` from onym-ios PR #166.
 */
@Serializable
data class GroupAvatarPayload(
    @SerialName("avatar_version")
    val version: Int,
    @SerialName("avatar_group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    /** Lowercase BLS pubkey hex (96 chars) of the admin who set the
     *  avatar. Informational only — never used to authenticate. */
    @SerialName("avatar_sender_bls_hex")
    val senderBlsHex: String,
    /** Milliseconds since the Unix epoch at send time. */
    @SerialName("avatar_sent_at_millis")
    val sentAtMillis: Long,
    /** Raw JPEG bytes, base64 on the wire. **Absent / nil = photo
     *  removed** (this message expresses the current state). */
    @SerialName("avatar")
    @Serializable(with = Base64ByteArraySerializer::class)
    val avatar: ByteArray? = null,
) {
    init {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is GroupAvatarPayload &&
            version == other.version &&
            groupId.contentEquals(other.groupId) &&
            senderBlsHex == other.senderBlsHex &&
            sentAtMillis == other.sentAtMillis &&
            (avatar?.contentEquals(other.avatar) ?: (other.avatar == null)))

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + senderBlsHex.hashCode()
        h = 31 * h + sentAtMillis.hashCode()
        h = 31 * h + (avatar?.contentHashCode() ?: 0)
        return h
    }
}
