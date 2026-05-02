package chat.onym.android.chain

import chat.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Wire-format pin for the relayer JSON payloads. **Field names are
 * load-bearing** — they must match `SEPContractTypes.swift` from
 * onym-ios PR #24 byte-for-byte (snake_case via [SerialName]).
 *
 * `ByteArray` fields are encoded as base64 strings via
 * [Base64ByteArraySerializer] (matches Foundation's default
 * `Data` Codable shape on iOS).
 *
 * UInt32 / UInt64 are serialised as JSON numbers via the experimental
 * unsigned support — kotlinx.serialization 1.6+ writes them as
 * positive integers, matching iOS Codable's UInt32 / UInt64 emission.
 *
 * Mirrors `SEPContractTypes.swift` from onym-ios PR #24.
 */

// ─── enums ────────────────────────────────────────────────────────

/**
 * Mirrors `SEPGroupType` from swift-mls plus the post-v0.0.3
 * [TYRANNY] case (group_type=4). Persisted as the `group_type` u32 in
 * the contract's `CommitmentEntryV2`.
 */
@Serializable(with = SepGroupTypeSerializer::class)
enum class SepGroupType(val rawValue: UInt) {
    ANARCHY(0u),
    ONE_ON_ONE(1u),
    DEMOCRACY(2u),
    OLIGARCHY(3u),
    TYRANNY(4u),
    ;

    companion object {
        fun fromRaw(raw: UInt): SepGroupType =
            entries.firstOrNull { it.rawValue == raw }
                ?: throw IllegalArgumentException("unknown SepGroupType raw=$raw")
    }
}

internal object SepGroupTypeSerializer : KSerializer<SepGroupType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SepGroupType", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: SepGroupType) {
        encoder.encodeInt(value.rawValue.toInt())
    }

    override fun deserialize(decoder: Decoder): SepGroupType =
        SepGroupType.fromRaw(decoder.decodeInt().toUInt())
}

/**
 * Tier sizing for the Merkle tree. Values pinned to match the VK
 * ceremonies. iOS uses Int rawValue but exposes derived
 * `maxMembers` / `depth`; we mirror.
 */
enum class SepTier(val rawValue: Int, val maxMembers: Int, val depth: Int) {
    SMALL(0, 32, 5),
    MEDIUM(1, 256, 8),
    LARGE(2, 2048, 11),
}

// ─── public-input bundles ─────────────────────────────────────────

/** Public-input bundle for the create circuit: commitment + epoch. */
@Serializable
data class SepPublicInputs(
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    val epoch: ULong,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SepPublicInputs) return false
        return commitment.contentEquals(other.commitment) && epoch == other.epoch
    }

    override fun hashCode(): Int =
        31 * commitment.contentHashCode() + epoch.hashCode()
}

/**
 * Public-input bundle for the update circuit (sep-mls #59 fix). The
 * contract rederives `c_new` from the proof itself, so the relayer no
 * longer trusts a client-supplied "new commitment".
 */
@Serializable
data class SepUpdatePublicInputs(
    @SerialName("c_old")
    @Serializable(with = Base64ByteArraySerializer::class)
    val cOld: ByteArray,
    @SerialName("epoch_old")
    val epochOld: ULong,
    @SerialName("c_new")
    @Serializable(with = Base64ByteArraySerializer::class)
    val cNew: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SepUpdatePublicInputs) return false
        return cOld.contentEquals(other.cOld) &&
            epochOld == other.epochOld &&
            cNew.contentEquals(other.cNew)
    }

    override fun hashCode(): Int {
        var h = cOld.contentHashCode()
        h = 31 * h + epochOld.hashCode()
        h = 31 * h + cNew.contentHashCode()
        return h
    }
}

// ─── request payloads ─────────────────────────────────────────────

/**
 * Payload for `create_group_v2`. Used by Anarchy, OneOnOne, Democracy,
 * AND Tyranny. Oligarchy uses its own dedicated request shape because
 * it seeds an extra admin root (out of scope for PR-A).
 */
@Serializable
data class SepCreateGroupV2Request(
    val caller: String,
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    val tier: UInt,
    @SerialName("group_type")
    val groupType: SepGroupType,
    @SerialName("member_count")
    val memberCount: UInt,
    @Serializable(with = Base64ByteArraySerializer::class)
    val proof: ByteArray,
    @SerialName("public_inputs")
    val publicInputs: SepPublicInputs,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SepCreateGroupV2Request) return false
        return caller == other.caller &&
            groupId.contentEquals(other.groupId) &&
            commitment.contentEquals(other.commitment) &&
            tier == other.tier &&
            groupType == other.groupType &&
            memberCount == other.memberCount &&
            proof.contentEquals(other.proof) &&
            publicInputs == other.publicInputs
    }

    override fun hashCode(): Int {
        var h = caller.hashCode()
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + commitment.contentHashCode()
        h = 31 * h + tier.hashCode()
        h = 31 * h + groupType.hashCode()
        h = 31 * h + memberCount.hashCode()
        h = 31 * h + proof.contentHashCode()
        h = 31 * h + publicInputs.hashCode()
        return h
    }
}

/** Payload for `update_commitment` (member-add / member-remove). */
@Serializable
data class SepUpdateCommitmentRequest(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val proof: ByteArray,
    @SerialName("public_inputs")
    val publicInputs: SepUpdatePublicInputs,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SepUpdateCommitmentRequest) return false
        return groupId.contentEquals(other.groupId) &&
            proof.contentEquals(other.proof) &&
            publicInputs == other.publicInputs
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + proof.contentHashCode()
        h = 31 * h + publicInputs.hashCode()
        return h
    }
}

/** Payload for `get_state` / `get_state_v2` / `get_admin_root`. */
@Serializable
data class SepGetStateRequest(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SepGetStateRequest) return false
        return groupId.contentEquals(other.groupId)
    }

    override fun hashCode(): Int = groupId.contentHashCode()
}

// ─── response payloads ────────────────────────────────────────────

/**
 * On-chain state returned by `get_state`. V1 entries (no group_type
 * metadata).
 */
@Serializable
data class SepCommitmentEntry(
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    val epoch: ULong,
    val timestamp: ULong,
    val tier: UInt,
    val active: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SepCommitmentEntry) return false
        return commitment.contentEquals(other.commitment) &&
            epoch == other.epoch &&
            timestamp == other.timestamp &&
            tier == other.tier &&
            active == other.active
    }

    override fun hashCode(): Int {
        var h = commitment.contentHashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + timestamp.hashCode()
        h = 31 * h + tier.hashCode()
        h = 31 * h + active.hashCode()
        return h
    }
}

/**
 * Relayer's response to a contract-invocation POST. `accepted`
 * reflects the contract's verification result; [transactionHash] is
 * set when a Soroban tx was actually submitted.
 *
 * **Note:** iOS `SEPSubmissionResponse` doesn't override CodingKeys,
 * so [transactionHash] is encoded as the verbatim camelCase field
 * (NOT snake_case `transaction_hash`). We mirror that exactly.
 */
@Serializable
data class SepSubmissionResponse(
    val accepted: Boolean,
    val transactionHash: String? = null,
    val message: String? = null,
)

// ─── invocation envelope ──────────────────────────────────────────

/**
 * Generic envelope wrapping a contract-function invocation. Mirrors
 * `swift-mls`'s `SEPContractInvocation` so the relayer wire format
 * stays in sync — the relayer reads `contract_id`, `function`, and
 * `payload` out of the JSON top-level.
 *
 * Stellar Soroban SDK is intentionally not pulled in: relayers handle
 * tx assembly + signing, this client just posts the function call.
 *
 * Generic over the payload type so each call site keeps its compile-
 * time payload shape; serialised through
 * [SepContractClient.invokeRaw].
 */
@Serializable
data class SepContractInvocation<P>(
    @SerialName("contract_id")
    val contractId: String,
    val function: String,
    val payload: P,
)
