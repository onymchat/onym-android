package app.onym.android.chain

import androidx.annotation.StringRes
import app.onym.android.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Soroban network the contract was deployed against. Wire format
 * uses lowercase tags — `"testnet"` / `"public"` (Stellar's name
 * for mainnet). Anything else is dropped at the fetcher boundary
 * (forwards-compatibility — a future `"futurenet"` we don't know
 * about doesn't break the picker for the networks we do).
 */
enum class ContractNetwork(
    val wireValue: String,
    @StringRes val displayNameResId: Int,
) {
    Testnet("testnet", R.string.network_testnet),
    Public("public", R.string.network_public);

    companion object {
        fun fromWire(value: String): ContractNetwork? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Governance scheme the contract implements. The five known kinds
 * line up with the SDK modules (`Anarchy` / `OneOnOne` /
 * `Tyranny` / `Democracy` / `Oligarchy`); a future sixth scheme
 * would also need new ZK proof code in the SDK, so silently
 * dropping unknowns at the boundary is correct — the picker
 * couldn't show it anyway.
 */
enum class GovernanceType(
    val wireValue: String,
    @StringRes val displayNameResId: Int,
) {
    Anarchy("anarchy", R.string.governance_anarchy),
    Democracy("democracy", R.string.governance_democracy),
    Oligarchy("oligarchy", R.string.governance_oligarchy),
    OneOnOne("oneonone", R.string.governance_oneonone),
    Tyranny("tyranny", R.string.governance_tyranny);

    companion object {
        fun fromWire(value: String): GovernanceType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One contract deployment: a `(network, type)` pair pointing at a
 * Soroban contract address. The version (release tag) lives on
 * the parent [ContractRelease].
 */
data class ContractEntry(
    val network: ContractNetwork,
    val type: GovernanceType,
    val id: String,
)

/**
 * One published release of `onymchat/onym-contracts`. `release` is
 * the tag (e.g. `"v0.0.2"`); `publishedAt` orders releases (newest
 * `publishedAt` wins the "default to latest" resolution rule);
 * `contracts` is the list of `(network, type, id)` triples
 * deployed in this release.
 */
data class ContractRelease(
    val release: String,
    val publishedAt: Instant,
    val contracts: List<ContractEntry>,
)

/**
 * Top-level shape of `contracts-manifest.json`. The `releases[]`
 * array is the union of all historical releases, newest-first by
 * convention but [ContractsManifest.Companion.fromRaw] re-sorts
 * defensively. Manifest is regenerated and re-attached to the
 * latest release on every new release of `onymchat/onym-contracts`
 * (CI step in the contracts repo).
 *
 * Mirrors the iOS twin's `ContractsManifest`.
 */
data class ContractsManifest(
    val version: Int,
    val releases: List<ContractRelease>,
) {
    companion object {
        /**
         * Decode a [RawContractsManifest] (string-typed, tolerates
         * unknown enum values), drop any contract entry whose
         * `network` or `type` doesn't match a known case, drop any
         * release whose `publishedAt` doesn't parse, and re-sort
         * by [Instant.compareTo] descending.
         *
         * This is the boundary that turns the wire format into the
         * typed domain — every consumer downstream (repository,
         * ViewModel, screens) sees only valid `ContractNetwork` /
         * `GovernanceType` values.
         */
        internal fun fromRaw(raw: RawContractsManifest): ContractsManifest {
            val typedReleases = raw.releases.mapNotNull { rawRelease ->
                val publishedAt = try {
                    Instant.parse(rawRelease.publishedAt)
                } catch (_: DateTimeParseException) {
                    return@mapNotNull null
                }
                val typedContracts = rawRelease.contracts.mapNotNull { rawEntry ->
                    val network = ContractNetwork.fromWire(rawEntry.network)
                        ?: return@mapNotNull null
                    val type = GovernanceType.fromWire(rawEntry.type)
                        ?: return@mapNotNull null
                    ContractEntry(network = network, type = type, id = rawEntry.id)
                }
                ContractRelease(
                    release = rawRelease.release,
                    publishedAt = publishedAt,
                    contracts = typedContracts,
                )
            }.sortedByDescending { it.publishedAt }
            return ContractsManifest(version = raw.version, releases = typedReleases)
        }
    }
}

// ─── Raw wire-format types (kotlinx.serialization throws on unknown
//     enum values, so we decode strings and map to enums above) ────

/**
 * Wire-format counterpart of [ContractsManifest]. String-typed for
 * `network` / `type` so unknown values can be filtered rather
 * than throwing during decode.
 */
@Serializable
internal data class RawContractsManifest(
    val version: Int,
    val releases: List<RawContractRelease> = emptyList(),
)

@Serializable
internal data class RawContractRelease(
    val release: String,
    @SerialName("publishedAt") val publishedAt: String,
    val contracts: List<RawContractEntry> = emptyList(),
)

@Serializable
internal data class RawContractEntry(
    val network: String,
    val type: String,
    val id: String,
)
