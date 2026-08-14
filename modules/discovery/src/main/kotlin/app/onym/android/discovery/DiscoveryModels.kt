package app.onym.android.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Document shapes for the Discovery seat's static-snapshot / Ed25519
 * implementation profile (`Discovery-Static-Ed25519.md` §4).
 *
 * Decoding is deliberately two-speed:
 *
 *  - **Top-level documents parse strictly** — an unknown top-level
 *    field on a provider manifest or catalog snapshot is
 *    `provider_manifest_invalid` / `snapshot_invalid`, so every decode
 *    goes through [DiscoveryJson.strict] (`ignoreUnknownKeys = false`,
 *    explicit — never a tolerant default).
 *  - **Catalog entries and `catalogs[]` descriptors decode lossily** —
 *    `entries` stays a `List<JsonElement>` on the snapshot and
 *    `catalogs` a `List<JsonElement>` on the manifest; each element is
 *    decoded individually by [DiscoveryTrust]. One malformed entry or
 *    descriptor is skipped (and counted), the document survives — but
 *    a manifest with zero surviving descriptors is
 *    `provider_manifest_invalid` (§4.1).
 *
 * Wire keys are camelCase, matching the Rust reference implementation
 * (`onym-discovery` `src/types.rs`) byte-for-byte on the fixtures.
 */
object DiscoveryJson {
    /**
     * Strict decoder for top-level documents. `ignoreUnknownKeys`
     * defaults to `false`, but the profile makes strictness a
     * *requirement* (an attacker smuggling extra fields must fail
     * closed), so it is pinned explicitly.
     */
    val strict: Json = Json { ignoreUnknownKeys = false }
}

/** Profile-wide constants (`Discovery-Static-Ed25519.md` §1/§7). */
object DiscoveryProfile {
    const val IMPLEMENTATION_PROFILE = "onym:discovery-implementation:static-ed25519-v1"

    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_SNAPSHOT_BYTES = 1024 * 1024
    const val MAX_DESTINATION_MANIFEST_BYTES = 256 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_EXPIRY_WINDOW_DAYS = 90L
}

/**
 * Provider manifest (`§4.1`) — self-signed root of a Discovery
 * source's trust. `signature` stays on the model (nullable) so the
 * strict decode accepts published documents; verification always
 * removes it *structurally* via [DiscoveryCanonical] before checking
 * the Ed25519 signature.
 */
@Serializable
data class DiscoveryProviderManifest(
    val version: Int,
    val implementationProfileId: String,
    val providerId: String,
    val operator: String,
    val seat: String,
    /** Lossy: descriptors are decoded individually by [DiscoveryTrust]
     *  so one malformed descriptor cannot take the manifest down with
     *  it (§4.1); zero surviving descriptors is invalid. */
    val catalogs: List<JsonElement>,
    val capabilities: List<String>,
    val privacyProfile: String,
    val privacyProfileUri: String,
    /** Offers are opaque at this layer (consumed by the consent flow
     *  in a later PR) — kept as raw elements, not dropped, so the
     *  strict top-level decode still sees the field. */
    val offers: List<JsonElement>,
    val validUntil: String,
    val signature: String? = null,
)

/** One declared catalog inside a provider manifest (`§4.1`). */
@Serializable
data class CatalogDescriptor(
    val catalogId: String,
    val snapshot: String,
    val audience: String,
    val seatTypes: List<String>,
    val policy: String,
    val policyUri: String,
)

/**
 * Catalog snapshot (`§4.2`). [entries] is intentionally
 * `List<JsonElement>`: each element is decoded individually and
 * lossily by [DiscoveryTrust.verifySnapshot] so one malformed entry
 * cannot take the snapshot down with it.
 */
@Serializable
data class CatalogSnapshot(
    val version: Int,
    val implementationProfileId: String,
    val catalogId: String,
    val providerId: String,
    val sequence: Long,
    val previousDigest: String? = null,
    val policyDigest: String,
    val generatedAt: String,
    val expiresAt: String,
    val entries: List<JsonElement>,
    val signature: String? = null,
)

/**
 * Disclosed commercial relationship between the Discovery provider
 * and a listed instance (`Discovery.md` §5.3). Wire format uses
 * kebab-case tags. An entry whose relationship doesn't match a known
 * case is **skipped, never defaulted** — advertising must not be able
 * to masquerade as `none` by inventing a tag the client doesn't know.
 */
@Serializable
enum class EntryRelationship(val wireValue: String) {
    @SerialName("none") None("none"),
    @SerialName("catalog-subscriber") CatalogSubscriber("catalog-subscriber"),
    @SerialName("listing-fee") ListingFee("listing-fee"),
    @SerialName("sponsored-placement") SponsoredPlacement("sponsored-placement"),
    @SerialName("common-owner") CommonOwner("common-owner"),
    @SerialName("catalog-sponsor") CatalogSponsor("catalog-sponsor"),
    @SerialName("other-disclosed") OtherDisclosed("other-disclosed");

    companion object {
        fun fromRaw(value: String): EntryRelationship? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** `manifest` reference inside a catalog entry (`§4.2`). */
@Serializable
data class ManifestRef(
    val uri: String,
    val digest: String,
)

/** One evidence pointer inside a catalog entry (`Discovery.md` §5.3). */
@Serializable
data class EvidenceRef(
    val type: String,
    val uri: String,
    val digest: String,
)

/**
 * Wire-shaped catalog entry DTO — string-typed `relationship` so an
 * unknown value surfaces as a skip decision instead of a decode
 * throw. Strict *within* the entry: an unknown field inside an entry
 * skips that entry (lossy), it does not fail the snapshot.
 *
 * Same Raw + [CatalogEntry.fromRaw] boundary pattern as
 * `RawContractsManifest` / `ContractsManifest.fromRaw` in :chain.
 */
@Serializable
internal data class RawCatalogEntry(
    val componentId: String,
    val seatType: String,
    val manifest: ManifestRef,
    val operator: String,
    val profiles: List<String> = emptyList(),
    val evidence: List<EvidenceRef> = emptyList(),
    val listedAt: String,
    val reviewedAt: String? = null,
    val relationship: String,
    val placement: String,
)

/**
 * Typed catalog entry — the only entry shape downstream consumers
 * (repository, seat adapters, UI) ever see. Every field has passed
 * [DiscoveryTrust]'s per-entry validation: component-id / operator-key
 * / digest syntax, §7 URI rules, RFC 3339 timestamps, and a known
 * [EntryRelationship].
 *
 * Timestamps stay validated RFC 3339 strings (not `Instant`) so the
 * type round-trips through the cached-entries DataStore blob exactly.
 */
@Serializable
data class CatalogEntry(
    val componentId: String,
    val seatType: String,
    val manifest: ManifestRef,
    val operator: String,
    val profiles: List<String> = emptyList(),
    val evidence: List<EvidenceRef> = emptyList(),
    val listedAt: String,
    val reviewedAt: String? = null,
    val relationship: EntryRelationship,
    val placement: String,
)
