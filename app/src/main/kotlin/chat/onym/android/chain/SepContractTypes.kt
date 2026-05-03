package chat.onym.android.chain

import chat.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format pin for the relayer JSON payloads. **Field names are
 * load-bearing** — every key here matches `RelayerRequest` /
 * `ContractType` / `Network` in `onym-relayer/src/handler.rs` +
 * `src/config.rs` byte-for-byte.
 *
 * `ByteArray` fields encode as base64 strings via
 * [Base64ByteArraySerializer] (the relayer's `decode_wire_bytes`
 * accepts both base64 and hex; we always emit base64 to match
 * Foundation's default `Data` Codable shape on iOS).
 *
 * Mirrors `SEPContractTypes.swift` from onym-ios PR #27.
 */

// ─── enums ────────────────────────────────────────────────────────

/**
 * On-chain governance flavour. The relayer
 * (`onym-relayer/src/config.rs`, `enum ContractType`) accepts the
 * lowercase string spelling on the wire — these `wireValue`s are
 * pinned to match. ONE_ON_ONE serializes as `"oneonone"` (no
 * separator) per the Rust enum's `label()`.
 *
 * The previous PR-A shipped this as a `UInt32` raw with a custom
 * serializer; PR #27 flips both wire + persistence to the string
 * shape — the iOS twin made the same hop. Older invitation payloads
 * that used the integer raw need the [intRawValue] convenience to
 * round-trip; new code uses the string everywhere.
 */
@Serializable
enum class SepGroupType(val wireValue: String) {
    @SerialName("anarchy") ANARCHY("anarchy"),
    @SerialName("oneonone") ONE_ON_ONE("oneonone"),
    @SerialName("democracy") DEMOCRACY("democracy"),
    @SerialName("oligarchy") OLIGARCHY("oligarchy"),
    @SerialName("tyranny") TYRANNY("tyranny"),
    ;

    /** Stable u32 identifier kept for older invitation-payload
     *  decoders that haven't been migrated to the string shape yet
     *  (the `groupTypeRaw` `UInt` field on PR-C's
     *  [chat.onym.android.group.GroupInvitationPayload] before the
     *  PR #27 flip). New persistence + wire code should NOT use this. */
    val intRawValue: UInt
        get() = when (this) {
            ANARCHY -> 0u
            ONE_ON_ONE -> 1u
            DEMOCRACY -> 2u
            OLIGARCHY -> 3u
            TYRANNY -> 4u
        }

    companion object {
        fun fromWire(value: String): SepGroupType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Tier sizing for the Merkle tree. Wire-encoded as the raw [Int] for
 * the `--tier` CLI arg the relayer forwards to the contract.
 */
enum class SepTier(val rawValue: Int, val maxMembers: Int, val depth: Int) {
    SMALL(0, 32, 5),
    MEDIUM(1, 256, 8),
    LARGE(2, 2048, 11),
}

/**
 * Stellar network the relayer should target. Wire-encoded as the
 * lowercase label (`testnet` or `public`) — `mainnet` is also
 * accepted by the relayer as an alias for `public`, but we always
 * send `public`.
 *
 * Mirrors `SEPNetwork` from onym-ios PR #27.
 */
@Serializable
enum class SepNetwork(val wireValue: String) {
    @SerialName("testnet") TESTNET("testnet"),
    @SerialName("public") PUBLIC_NET("public"),
}

// ─── invocation envelope ──────────────────────────────────────────

/**
 * Generic envelope the relayer expects on `POST /`. Top-level shape
 * (mirrors `RelayerRequest` in `onym-relayer/src/handler.rs`):
 *
 * ```json
 * {
 *   "contractID":   "C…",
 *   "contractType": "tyranny",
 *   "network":      "testnet",
 *   "function":     "create_group",
 *   "payload":      { …function-specific… }
 * }
 * ```
 *
 * **camelCase top-level keys** — the relayer's `serde(rename_all =
 * "camelCase")` accepts `contractID` (with snake_case aliases for
 * back-compat). We always send the camelCase form; iOS does the same.
 */
@Serializable
data class SepContractInvocation<P>(
    @SerialName("contractID") val contractID: String,
    @SerialName("contractType") val contractType: SepGroupType,
    val network: SepNetwork,
    val function: String,
    val payload: P,
)

// ─── per-function payloads ────────────────────────────────────────

/**
 * `create_group` payload for the Tyranny contract. Differs from the
 * Anarchy / 1-on-1 / Democracy shape — Tyranny needs the Poseidon
 * `admin_pubkey_commitment` (32 B) as a separate CLI arg AND in the
 * 4-element public-inputs vector that the contract verifies.
 *
 * The PI vector is sent as 4 ByteArrays (each 32 bytes,
 * JSON-encoded as base64 strings):
 * `[commitment, fr_zero (= 32 zero bytes), admin_pubkey_commitment, group_id_fr]`
 * — the SDK's 128-byte `Tyranny.CreateProof.publicInputs` blob split
 * into 4 chunks. Relayer handler:
 * `build_public_inputs_from_object` → `ContractType::Tyranny` arm.
 *
 * Mirrors `TyrannyCreateGroupPayload` from onym-ios PR #27.
 */
@Serializable
data class TyrannyCreateGroupPayload(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    val tier: Int,
    @SerialName("admin_pubkey_commitment")
    @Serializable(with = Base64ByteArraySerializer::class)
    val adminPubkeyCommitment: ByteArray,
    /** 1601-byte raw PLONK proof — the relayer's
     *  `decode_wire_bytes(_, _, Some(1601))` rejects anything else. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val proof: ByteArray,
    /** 4 elements × 32 bytes — see comment above. */
    val publicInputs: List<@Serializable(with = Base64ByteArraySerializer::class) ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TyrannyCreateGroupPayload) return false
        return groupId.contentEquals(other.groupId) &&
            commitment.contentEquals(other.commitment) &&
            tier == other.tier &&
            adminPubkeyCommitment.contentEquals(other.adminPubkeyCommitment) &&
            proof.contentEquals(other.proof) &&
            publicInputs.size == other.publicInputs.size &&
            publicInputs.zip(other.publicInputs).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + commitment.contentHashCode()
        h = 31 * h + tier
        h = 31 * h + adminPubkeyCommitment.contentHashCode()
        h = 31 * h + proof.contentHashCode()
        for (p in publicInputs) h = 31 * h + p.contentHashCode()
        return h
    }
}

/**
 * `create_group` payload for the OneOnOne (1v1) contract. Layout
 * mirrors `add_create_group_args` in `onym-relayer/src/handler.rs`,
 * `ContractType::OneOnOne` arm — the relayer extracts `caller` from
 * its config and `group_id` / `commitment` from this payload's
 * top-level keys, then forwards `--proof` + `--public-inputs` to the
 * `sep-oneonone` Soroban contract.
 *
 * No `tier` (depth=5 is hardcoded in the OneOnOne circuit) and no
 * `admin_pubkey_commitment` (1v1 has no admin role). The PI vector is
 * 2 × 32B chunks: `[commitment, fr_zero]` — `fr_zero` is the symmetric
 * second-element placeholder the OneOnOne contract validates.
 *
 * Mirrors `OneOnOneCreateGroupPayload` from onym-ios PR #36.
 */
@Serializable
data class OneOnOneCreateGroupPayload(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    /** 1601-byte raw PLONK proof — same wire constraint as Tyranny. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val proof: ByteArray,
    /** 2 elements × 32 bytes — `[commitment, fr_zero]`. */
    val publicInputs: List<@Serializable(with = Base64ByteArraySerializer::class) ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OneOnOneCreateGroupPayload) return false
        return groupId.contentEquals(other.groupId) &&
            commitment.contentEquals(other.commitment) &&
            proof.contentEquals(other.proof) &&
            publicInputs.size == other.publicInputs.size &&
            publicInputs.zip(other.publicInputs).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + commitment.contentHashCode()
        h = 31 * h + proof.contentHashCode()
        for (p in publicInputs) h = 31 * h + p.contentHashCode()
        return h
    }
}

/**
 * `create_group` payload for the Anarchy (open governance) contract.
 * Layout mirrors `add_create_group_args` in `onym-relayer/src/handler.rs`,
 * `ContractType::Anarchy` arm — the relayer extracts `caller` from
 * its config, `group_id` / `commitment` / `tier` / `member_count` from
 * this payload's top-level keys, then forwards `--proof` +
 * `--public-inputs` to the `sep-anarchy` Soroban contract.
 *
 * Differs from [TyrannyCreateGroupPayload]: no `admin_pubkey_commitment`
 * (Anarchy has no admin role). Differs from [OneOnOneCreateGroupPayload]:
 * has `tier` (Anarchy supports all three depths) AND `member_count`
 * (informational — supplied at create_group, never updated by the
 * contract; `0` means "not tracked").
 *
 * The PI vector is 2 × 32B chunks: `[commitment, fr_zero]` — same
 * shape the contract's `MEMBERSHIP_PI_COUNT = 2` checks against
 * (the second element is the membership-proof `epoch=0` Fr).
 */
@Serializable
data class AnarchyCreateGroupPayload(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    val tier: Int,
    /** Informational only on the contract side. `0` = "not tracked"
     *  (the v1 sep-xxxx sentinel). The relayer defaults to `0` when
     *  absent, but we send the real count so future occupancy logic
     *  has accurate state to read back. */
    @SerialName("member_count")
    val memberCount: Int,
    /** 1601-byte raw PLONK proof. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val proof: ByteArray,
    /** 2 elements × 32 bytes — `[commitment, fr_zero]`. */
    val publicInputs: List<@Serializable(with = Base64ByteArraySerializer::class) ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnarchyCreateGroupPayload) return false
        return groupId.contentEquals(other.groupId) &&
            commitment.contentEquals(other.commitment) &&
            tier == other.tier &&
            memberCount == other.memberCount &&
            proof.contentEquals(other.proof) &&
            publicInputs.size == other.publicInputs.size &&
            publicInputs.zip(other.publicInputs).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + commitment.contentHashCode()
        h = 31 * h + tier
        h = 31 * h + memberCount
        h = 31 * h + proof.contentHashCode()
        for (p in publicInputs) h = 31 * h + p.contentHashCode()
        return h
    }
}

/**
 * `update_commitment` payload — Tyranny variant. The SDK's
 * `Tyranny.UpdateProof.publicInputs` is 160 bytes = 5 × 32B
 * (`c_old || epoch_old || c_new || admin_pubkey_commitment || group_id_fr`).
 * Not used in PR-C; lives here so the chain seam is complete.
 */
@Serializable
data class TyrannyUpdateCommitmentPayload(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val proof: ByteArray,
    val publicInputs: List<@Serializable(with = Base64ByteArraySerializer::class) ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TyrannyUpdateCommitmentPayload) return false
        return groupId.contentEquals(other.groupId) &&
            proof.contentEquals(other.proof) &&
            publicInputs.size == other.publicInputs.size &&
            publicInputs.zip(other.publicInputs).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + proof.contentHashCode()
        for (p in publicInputs) h = 31 * h + p.contentHashCode()
        return h
    }
}

/** Payload for `get_commitment`. */
@Serializable
data class GetCommitmentPayload(
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GetCommitmentPayload) return false
        return groupId.contentEquals(other.groupId)
    }

    override fun hashCode(): Int = groupId.contentHashCode()
}

// ─── responses ────────────────────────────────────────────────────

/**
 * On-chain state returned by `get_commitment`. The contract-side
 * `CommitmentEntry` shape varies per governance type — only
 * [commitment] and [epoch] are present in every variant. The rest
 * are decoded if present:
 *
 * | Field        | anarchy | 1v1 | tyranny | democracy | oligarchy |
 * |--------------|---------|-----|---------|-----------|-----------|
 * | `commitment` | ✅      | ✅  | ✅      | ✅        | ✅        |
 * | `epoch`      | ✅      | ✅  | ✅      | ✅        | ✅        |
 * | `timestamp`  | varies  | ✅  | ✅      | ✅        | ✅        |
 * | `tier`       | varies  | —   | ✅      | ✅        | ✅        |
 * | `active`     | —       | —   | —       | ✅        | ✅        |
 *
 * Mirrors `SEPCommitmentEntry` from onym-ios PR #36 — every optional
 * field is `?` so the relayer can omit it without the client refusing
 * to decode (which is how release run #25271977084 surfaced this:
 * `MissingFieldException: Field 'active' is required` against a
 * tyranny `get_commitment` response that doesn't ship `active`).
 */
@Serializable
data class SepCommitmentEntry(
    @Serializable(with = Base64ByteArraySerializer::class)
    val commitment: ByteArray,
    val epoch: ULong,
    val timestamp: ULong? = null,
    val tier: UInt? = null,
    val active: Boolean? = null,
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
        h = 31 * h + (timestamp?.hashCode() ?: 0)
        h = 31 * h + (tier?.hashCode() ?: 0)
        h = 31 * h + (active?.hashCode() ?: 0)
        return h
    }
}

/**
 * Relayer's response to a contract-invocation POST. Mirrors
 * `RelayerResponse` in `onym-relayer/src/handler.rs` — top-level
 * camelCase with optional `transactionHash` and `message`.
 */
@Serializable
data class SepSubmissionResponse(
    val accepted: Boolean,
    val transactionHash: String? = null,
    val message: String? = null,
)
