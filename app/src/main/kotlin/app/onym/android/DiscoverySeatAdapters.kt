package app.onym.android

import app.onym.android.chain.KnownRelayersFetcher
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.DiscoveryFetching
import app.onym.android.discovery.DiscoveryProfile
import app.onym.android.discovery.DiscoveryRepository
import app.onym.android.foundation.ServiceManifestReviewer
import app.onym.android.foundation.SignedServiceManifest
import app.onym.android.transport.blossom.BlossomServerEndpoint
import app.onym.android.transport.blossom.KnownBlossomServersFetcher
import app.onym.android.transport.nostr.KnownNostrRelaysFetcher
import app.onym.android.transport.nostr.NostrRelayEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * Discovery-backed implementations of the three seat "known list"
 * fetcher interfaces. Lives in :app — the one place all the modules
 * meet — so no seat module (:chain / :transport-nostr /
 * :transport-blossom) gains a :discovery dependency.
 *
 * Each adapter asks the verified discovery aggregate for entries of
 * its seat type, fetches every entry's destination manifest, verifies
 * it against the catalog-pinned digest ([ServiceManifestReviewer] —
 * hard-enforced), and maps the manifest to the seat's endpoint type.
 * On any failure — no catalog entries, unreachable manifests, digest
 * or signature mismatches, unusable endpoint fields — the adapter
 * falls back to the wrapped legacy GitHub-releases fetcher, so
 * discovery can only ever *add* to what the app can do today, never
 * take away.
 *
 * `ContractsManifestFetcher` is deliberately NOT adapted: contracts
 * stay legacy-only in this effort (see the discovery design plan).
 * There is no moderation adapter either — the moderation seat has no
 * running code on Android.
 *
 * Mirrors `DiscoverySeatAdapters.swift` in onym-ios (minus the
 * moderation seat).
 */

// ─── Shared catalog seam ──────────────────────────────────────────

/**
 * The two capabilities every adapter needs, as suspending closures so
 * app-target tests can stub them without a repository or network:
 * entry lookup by seat type, and manifest-bytes fetch by URI.
 */
class DiscoverySeatCatalog(
    val entries: suspend (seatType: String) -> List<AttributedCatalogEntry>,
    val fetchManifestBytes: suspend (uri: String) -> ByteArray,
) {
    /**
     * Production wiring: entries from the repository's verified
     * aggregate; manifest bytes through the discovery fetcher
     * (destination manifests have their own §7 size cap — they are
     * small signed JSON documents, not media).
     */
    constructor(repository: DiscoveryRepository, fetcher: DiscoveryFetching) : this(
        entries = { seatType ->
            repository.snapshots.value.entries.filter { it.entry.seatType == seatType }
        },
        fetchManifestBytes = { uri ->
            fetcher.fetch(uri, DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES)
        },
    )

    /**
     * Fetch + review the destination manifest of every catalog entry
     * for [seatType]. Per-entry failures (unreachable, digest
     * mismatch, bad signature, expired, operator-key mismatch) skip
     * that entry; the caller falls back to legacy when nothing
     * survives. Skips are silent by design — the adapter's contract
     * is "never make the app worse", and the legacy list is always
     * there to fall back on. (No `android.util.Log` here: the
     * adapters are covered by plain-JUnit JVM tests where the Android
     * log stub throws.)
     */
    suspend fun reviewedEntries(seatType: String): List<ReviewedSeatEntry> {
        val reviewer = ServiceManifestReviewer()
        val result = mutableListOf<ReviewedSeatEntry>()
        for (attributed in entries(seatType)) {
            val entry = attributed.entry
            try {
                val bytes = fetchManifestBytes(entry.manifest.uri)
                // Hard-enforced review: digest pin from the *verified*
                // catalog entry, embedded operator signature over the
                // exact bytes, expiry.
                val reviewed = reviewer.review(raw = bytes, expectedDigest = entry.manifest.digest)
                // The catalog is the verified side of this trust
                // chain, so the entry's operator key is authoritative:
                // a fetched manifest naming a different key is
                // rejected, digest pin notwithstanding.
                if (reviewed.signedManifest.operatorKey != entry.operator) continue
                result.add(
                    ReviewedSeatEntry(
                        attributed = attributed,
                        manifest = reviewed.signedManifest,
                        fields = SeatManifestFields(reviewed.signedManifest.rawBytes),
                    ),
                )
            } catch (_: Exception) {
                // Per-entry skip; zero survivors → legacy fallback.
            }
        }
        return result
    }
}

/**
 * One catalog entry whose destination manifest passed review, with
 * the seat-specific fields already projected out of the exact bytes.
 */
class ReviewedSeatEntry(
    val attributed: AttributedCatalogEntry,
    val manifest: SignedServiceManifest,
    val fields: SeatManifestFields,
) {
    /** Display name: the manifest's `name` when the operator
     *  published one, else the component id minus its
     *  `onym:component:` prefix. */
    val displayName: String
        get() {
            fields.name?.takeIf { it.isNotEmpty() }?.let { return it }
            return attributed.entry.componentId.removePrefix("onym:component:")
        }

    /** First endpoint URI whose URL scheme is in [schemes]. */
    fun firstEndpointUri(schemes: Set<String>): String? =
        fields.firstEndpointUri(schemes)
}

/**
 * Seat-specific fields projected out of a reviewed manifest's exact
 * bytes. [SignedServiceManifest] only parses the common spine
 * (componentId / seat / operator / offers / validUntil) and keeps
 * everything else inside `rawBytes` for seat adapters to interpret —
 * this is that interpretation. Deliberately tolerant: a manifest
 * whose seat-specific fields don't match the expected shape yields
 * empty projections here, the entry maps to nothing, and the adapter
 * falls back to legacy.
 */
class SeatManifestFields(rawBytes: ByteArray) {
    /** Top-level `name`, when published. */
    val name: String?

    /** `endpoints[].uri`, in published order. */
    val endpointUris: List<String>

    /** Top-level `networks` (notary seat: which Stellar networks the
     *  relayer serves), when published. */
    val networks: List<String>?

    init {
        val obj = try {
            Json.parseToJsonElement(String(rawBytes, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }
        name = obj?.stringField("name")
        endpointUris = (obj?.get("endpoints") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.stringField("uri") }
            ?: emptyList()
        networks = (obj?.get("networks") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    /** First endpoint URI whose URL scheme is in [schemes]. */
    fun firstEndpointUri(schemes: Set<String>): String? =
        endpointUris.firstOrNull { uri ->
            val scheme = try {
                URI(uri).scheme
            } catch (_: Exception) {
                null
            }
            scheme?.lowercase() in schemes
        }

    private companion object {
        fun JsonObject.stringField(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

// ─── Notary seat (chain relayers) ─────────────────────────────────

/**
 * [KnownRelayersFetcher] backed by discovery entries with seat type
 * "notary"; legacy `relayers.json` on fallback.
 */
class DiscoveryBackedKnownRelayersFetcher(
    private val catalog: DiscoverySeatCatalog,
    private val fallback: KnownRelayersFetcher,
) : KnownRelayersFetcher {

    override suspend fun fetch(): List<RelayerEndpoint> {
        val endpoints = catalog.reviewedEntries(SEAT_TYPE).mapNotNull { reviewed ->
            reviewed.firstEndpointUri(setOf("https"))?.let { url ->
                RelayerEndpoint(
                    name = reviewed.displayName,
                    url = url,
                    networks = reviewed.fields.networks ?: DEFAULT_NETWORKS,
                )
            }
        }
        return endpoints.ifEmpty { fallback.fetch() }
    }

    companion object {
        const val SEAT_TYPE = "notary"

        /** A manifest without a `networks` field predates that field;
         *  the published relayers all serve both networks today, so
         *  this matches what `relayers.json` would say. */
        val DEFAULT_NETWORKS = listOf("testnet", "public")

        /** Wrap [fallback] when a discovery catalog is available;
         *  identity when it isn't (UI-test harness). */
        fun wrapping(
            fallback: KnownRelayersFetcher,
            catalog: DiscoverySeatCatalog?,
        ): KnownRelayersFetcher =
            if (catalog == null) fallback
            else DiscoveryBackedKnownRelayersFetcher(catalog = catalog, fallback = fallback)
    }
}

// ─── Message-transport seat (Nostr relays) ────────────────────────

/**
 * [KnownNostrRelaysFetcher] backed by discovery entries with seat
 * type "transport.message"; legacy `nostr-relays.json` on fallback.
 * The fallback is nullable because the app wiring's legacy fetcher
 * is itself nullable (null → no network refresh, hardcoded seed
 * stays) — zero discovery survivors with no fallback yields an empty
 * list, which the repository ignores, keeping the on-device list.
 */
class DiscoveryBackedKnownNostrRelaysFetcher(
    private val catalog: DiscoverySeatCatalog,
    private val fallback: KnownNostrRelaysFetcher?,
) : KnownNostrRelaysFetcher {

    override suspend fun fetch(): List<NostrRelayEndpoint> {
        val endpoints = catalog.reviewedEntries(SEAT_TYPE).mapNotNull { reviewed ->
            reviewed.firstEndpointUri(setOf("wss"))?.let { url ->
                // This list plays the published-default role (the
                // repository installs it while the user hasn't
                // customised), so the rows carry the "Default" badge —
                // same as entries from `nostr-relays.json`.
                NostrRelayEndpoint(url = url, name = reviewed.displayName, isDefault = true)
            }
        }
        return endpoints.ifEmpty { fallback?.fetch() ?: emptyList() }
    }

    companion object {
        const val SEAT_TYPE = "transport.message"

        /** Wrap [fallback] when a discovery catalog is available;
         *  identity when it isn't (UI-test harness). */
        fun wrapping(
            fallback: KnownNostrRelaysFetcher?,
            catalog: DiscoverySeatCatalog?,
        ): KnownNostrRelaysFetcher? =
            if (catalog == null) fallback
            else DiscoveryBackedKnownNostrRelaysFetcher(catalog = catalog, fallback = fallback)
    }
}

// ─── Blob-storage seat (Blossom servers) ──────────────────────────

/**
 * [KnownBlossomServersFetcher] backed by discovery entries with seat
 * type "blob.storage"; legacy `blossom-servers.json` on fallback.
 * Same nullable-fallback contract as the Nostr adapter.
 */
class DiscoveryBackedKnownBlossomServersFetcher(
    private val catalog: DiscoverySeatCatalog,
    private val fallback: KnownBlossomServersFetcher?,
) : KnownBlossomServersFetcher {

    override suspend fun fetch(): List<BlossomServerEndpoint> {
        val endpoints = catalog.reviewedEntries(SEAT_TYPE).mapNotNull { reviewed ->
            reviewed.firstEndpointUri(setOf("https"))?.let { url ->
                BlossomServerEndpoint(url = url, name = reviewed.displayName, isDefault = true)
            }
        }
        return endpoints.ifEmpty { fallback?.fetch() ?: emptyList() }
    }

    companion object {
        const val SEAT_TYPE = "blob.storage"

        /** Wrap [fallback] when a discovery catalog is available;
         *  identity when it isn't (UI-test harness). */
        fun wrapping(
            fallback: KnownBlossomServersFetcher?,
            catalog: DiscoverySeatCatalog?,
        ): KnownBlossomServersFetcher? =
            if (catalog == null) fallback
            else DiscoveryBackedKnownBlossomServersFetcher(catalog = catalog, fallback = fallback)
    }
}
