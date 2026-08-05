package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext payload the admin seals (via
 * `IdentityRepository.sealInvitation`, X25519 + AES-GCM, signed by
 * the admin's Ed25519 stellar key) and ships to every member's inbox
 * after removing a member from a Tyranny group. Tells receivers
 * "this person is no longer in the group — tombstone them and (for
 * remaining members) rotate to the fresh group secret".
 *
 * ## Two audience variants of the same type
 *
 * The admin encodes this payload twice per removal:
 *
 *  - **Remaining members** get the full variant: [groupSecretNew] +
 *    [saltNew] carry the rotated secrets so their local group state
 *    stays coherent (and any future key derivation from
 *    [ChatGroup.groupSecret] excludes the removed member).
 *  - **The removed member** gets the secret-free variant — both
 *    nullable fields omitted from the wire entirely (the encoder
 *    uses `encodeDefaults = false`). The victim learns *that* they
 *    were removed, never the post-removal secrets. This mirrors the
 *    `SEPRemovalNotice` / `SEPRekeyEnvelope` split from stellar-mls
 *    collapsed into one type, safe here because every payload is
 *    per-recipient sealed anyway.
 *
 * ## Trust
 *
 * Receivers MUST cross-check the outer `SealedEnvelope`'s verified
 * `senderEd25519PublicKey` against the group's stored
 * `adminEd25519PubkeyHex` (the dispatcher's `senderAuthorizedToMutate`
 * gate) AND verify `commitment + epoch` against the on-chain state
 * before mutating local state. That logic lives in the dispatcher —
 * this type is a pure value carrier.
 *
 * ## Wire disjointness
 *
 * Every REQUIRED key is prefixed `removal_` so the dispatcher's
 * trial-decode chain can never confuse this payload with any of the
 * other inbox payloads in either direction (crucially:
 * `removal_group_id` not `group_id`, `group_secret_new` not
 * `group_secret`).
 *
 * ## Versioning
 *
 * `removal_version = 1` is the only shape receivers handle today.
 * Future fields land via nullable defaults.
 *
 * ## Cross-platform parity
 *
 * Wire format authored on Android first (this file); onym-ios
 * mirrors the snake_case keys + base64 [ByteArray] encoding as
 * `MemberRemovalPayload.swift`.
 */
@Serializable
data class MemberRemovalPayload(
    @SerialName("removal_version")
    val version: Int,
    /** 32-byte group ID — receivers cross-check against their local
     *  [ChatGroup.groupIdBytes] and drop removals for groups they
     *  don't know about. */
    @SerialName("removal_group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    /** Lowercase 96-char BLS pubkey hex of the removed member — the
     *  stable cross-device identifier, and the dedup key into
     *  [ChatGroup.memberProfiles]. A receiver whose own BLS key
     *  matches is the removal target. */
    @SerialName("removal_member_bls_hex")
    val removedBlsHex: String,
    /** 32-byte Poseidon commitment of the post-removal tree, as
     *  anchored on chain by the admin's `update_commitment`. */
    @SerialName("removal_commitment")
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    /** Post-removal epoch (`epoch_old + 1`). Verified against the
     *  on-chain entry with the same converge-forward gate as member
     *  announcements. */
    @SerialName("removal_epoch")
    val epoch: ULong,
    /** Admin's wall clock at send time (informational — receivers
     *  order by arrival, and idempotency keys off epoch). */
    @SerialName("removal_sent_at_millis")
    val sentAtMillis: Long,
    /** Rotated 32-byte group secret. Remaining-members variant only —
     *  NEVER present on the copy sealed to the removed member. */
    @SerialName("group_secret_new")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupSecretNew: ByteArray? = null,
    /** Rotated 32-byte salt matching the post-removal commitment.
     *  Remaining-members variant only, like [groupSecretNew]. */
    @SerialName("salt_new")
    @Serializable(with = Base64ByteArraySerializer::class)
    val saltNew: ByteArray? = null,
) {
    init {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
        require(removedBlsHex.length == 96) {
            "removedBlsHex: expected 96 hex chars, got ${removedBlsHex.length}"
        }
        require(commitment.size == 32) {
            "commitment: expected 32 bytes, got ${commitment.size}"
        }
        groupSecretNew?.let {
            require(it.size == 32) {
                "groupSecretNew: expected 32 bytes, got ${it.size}"
            }
        }
        saltNew?.let {
            require(it.size == 32) {
                "saltNew: expected 32 bytes, got ${it.size}"
            }
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is MemberRemovalPayload &&
            version == other.version &&
            groupId.contentEquals(other.groupId) &&
            removedBlsHex == other.removedBlsHex &&
            commitment.contentEquals(other.commitment) &&
            epoch == other.epoch &&
            sentAtMillis == other.sentAtMillis &&
            (groupSecretNew?.contentEquals(other.groupSecretNew) ?: (other.groupSecretNew == null)) &&
            (saltNew?.contentEquals(other.saltNew) ?: (other.saltNew == null)))

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + removedBlsHex.hashCode()
        h = 31 * h + commitment.contentHashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + sentAtMillis.hashCode()
        h = 31 * h + (groupSecretNew?.contentHashCode() ?: 0)
        h = 31 * h + (saltNew?.contentHashCode() ?: 0)
        return h
    }
}
