package app.onym.android

import app.onym.android.chain.RelayerEndpoint
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.CatalogEntry
import app.onym.android.discovery.DiscoveryProfile
import app.onym.android.discovery.DiscoveryRepository
import app.onym.android.discovery.EntryRelationship
import app.onym.android.discovery.ManifestRef
import app.onym.android.discovery.SourceAttribution
import app.onym.android.foundation.ServiceManifestCanonical
import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import app.onym.android.transport.blossom.BlossomServerEndpoint
import app.onym.android.transport.blossom.KnownBlossomServersFetcher
import app.onym.android.transport.nostr.KnownNostrRelaysFetcher
import app.onym.android.transport.nostr.NostrRelayEndpoint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Unit tests for the discovery-backed seat adapters: catalog-entry →
 * endpoint mapping, hard-enforced destination-manifest review
 * (catalog digest pin, embedded operator signature, expiry,
 * catalog↔manifest operator-key equality), per-entry skip, and the
 * wrapped-legacy fallback that guarantees discovery never makes the
 * app worse.
 *
 * Manifests are signed in-test with BouncyCastle Ed25519 over
 * [ServiceManifestCanonical] signing bytes — the same helper pattern
 * as :foundation's `ServiceManifestTestSigning` (which lives in that
 * module's own src/test and is not shared as a fixture).
 */
class DiscoverySeatAdaptersTest {

    // ─── In-test Ed25519 manifest signing ─────────────────────────

    private val operatorPrivateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val strangerPrivateKey =
        Ed25519PrivateKeyParameters(ByteArray(32) { (it + 100).toByte() }, 0)

    private fun Ed25519PrivateKeyParameters.publicKeyHex(): String =
        generatePublicKey().encoded.joinToString("") { "%02x".format(it) }

    private val operatorKey get() = "onym:key:${operatorPrivateKey.publicKeyHex()}"
    private val strangerKey get() = "onym:key:${strangerPrivateKey.publicKeyHex()}"

    private fun sign(message: ByteArray, key: Ed25519PrivateKeyParameters): ByteArray {
        val signer = Ed25519Signer().apply { init(true, key) }
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Encode [unsigned], sign its canonical bytes with [key], and
     *  return the manifest bytes with the base64 signature embedded. */
    private fun signedManifestBytes(
        unsigned: JsonObject,
        key: Ed25519PrivateKeyParameters = operatorPrivateKey,
    ): ByteArray {
        val unsignedBytes = Json.encodeToString(JsonElement.serializer(), unsigned)
            .toByteArray(Charsets.UTF_8)
        val signature = sign(ServiceManifestCanonical.signingBytes(unsignedBytes), key)
        val signed = JsonObject(
            unsigned + ("signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))),
        )
        return Json.encodeToString(JsonElement.serializer(), signed).toByteArray(Charsets.UTF_8)
    }

    private fun destinationManifest(
        componentId: String = "onym:component:test-notary",
        seat: String = "notary",
        operator: String = operatorKey,
        name: String? = "Test Operator",
        endpointUris: List<String> = listOf("https://relayer.example.com"),
        networks: List<String>? = null,
        validUntil: String = "2999-12-31T23:59:59Z",
    ): JsonObject = buildJsonObject {
        put("version", 1)
        put("componentId", componentId)
        put("seat", seat)
        put("operator", operator)
        if (name != null) put("name", name)
        put(
            "endpoints",
            buildJsonArray {
                endpointUris.forEach { uri -> addJsonObject { put("uri", uri) } }
            },
        )
        if (networks != null) {
            put("networks", buildJsonArray { networks.forEach { add(it) } })
        }
        put("validUntil", validUntil)
    }

    private fun sha256Digest(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ─── Catalog-entry helpers ────────────────────────────────────

    private fun attributedEntry(
        componentId: String = "onym:component:test-notary",
        seatType: String = "notary",
        manifestUri: String = "https://operator.example.com/manifest.json",
        digest: String,
        operator: String = operatorKey,
    ): AttributedCatalogEntry = AttributedCatalogEntry(
        entry = CatalogEntry(
            componentId = componentId,
            seatType = seatType,
            manifest = ManifestRef(uri = manifestUri, digest = digest),
            operator = operator,
            listedAt = "2026-01-01T00:00:00Z",
            relationship = EntryRelationship.None,
            placement = "organic",
        ),
        attribution = SourceAttribution(
            providerId = "onym:component:test-provider",
            sourceLabel = "Test Provider",
            catalogId = "onym:catalog:test",
            snapshotDigest = "sha256:" + "0".repeat(64),
            relationship = EntryRelationship.None,
            placement = "organic",
        ),
    )

    /** Catalog over static entries + a [FakeDiscoveryFetcher] for the
     *  destination-manifest bytes (same seam the production
     *  constructor uses, minus the repository). */
    private fun catalogOf(
        entries: List<AttributedCatalogEntry>,
        fetcher: FakeDiscoveryFetcher,
    ): DiscoverySeatCatalog = DiscoverySeatCatalog(
        entries = { seatType -> entries.filter { it.entry.seatType == seatType } },
        fetchManifestBytes = { uri ->
            fetcher.fetch(uri, DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES)
        },
    )

    private fun relayersFallback(
        list: List<RelayerEndpoint> = listOf(
            RelayerEndpoint(
                name = "Legacy",
                url = "https://legacy.example.com",
                networks = listOf("testnet", "public"),
            ),
        ),
    ) = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(list))

    private class RecordingNostrFallback(
        private val list: List<NostrRelayEndpoint>,
    ) : KnownNostrRelaysFetcher {
        var fetchCallCount = 0
        override suspend fun fetch(): List<NostrRelayEndpoint> {
            fetchCallCount += 1
            return list
        }
    }

    private class RecordingBlossomFallback(
        private val list: List<BlossomServerEndpoint>,
    ) : KnownBlossomServersFetcher {
        var fetchCallCount = 0
        override suspend fun fetch(): List<BlossomServerEndpoint> {
            fetchCallCount += 1
            return list
        }
    }

    // ─── Notary seat (chain relayers) ─────────────────────────────

    @Test
    fun relayers_productionCatalogShape_mapsReviewedEntry() = runTest {
        // Through the real DiscoveryRepository + testFixtures fakes:
        // cached entries hydrate from InMemoryDiscoveryStore on
        // bootstrap (no source, no network), manifest bytes come from
        // FakeDiscoveryFetcher — the exact production catalog seam.
        val manifestUri = "https://operator.example.com/manifest.json"
        val bytes = signedManifestBytes(destinationManifest(networks = listOf("testnet")))
        val store = InMemoryDiscoveryStore()
        store.saveCachedEntries(listOf(attributedEntry(digest = sha256Digest(bytes))))
        val fetcher = FakeDiscoveryFetcher().apply { respond(manifestUri, bytes) }
        val repository = DiscoveryRepository(store = store, fetcher = fetcher)
        repository.bootstrap()

        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = DiscoverySeatCatalog(repository = repository, fetcher = fetcher),
            fallback = fallback,
        )

        val endpoints = adapter.fetch()

        assertEquals(
            listOf(
                RelayerEndpoint(
                    name = "Test Operator",
                    url = "https://relayer.example.com",
                    networks = listOf("testnet"),
                ),
            ),
            endpoints,
        )
        assertEquals(0, fallback.fetchCallCount)
    }

    @Test
    fun relayers_manifestWithoutNetworks_defaultsToBothNetworks() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        val bytes = signedManifestBytes(destinationManifest(networks = null))
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(listOf(attributedEntry(digest = sha256Digest(bytes))), fetcher),
            fallback = relayersFallback(),
        )

        assertEquals(
            DiscoveryBackedKnownRelayersFetcher.DEFAULT_NETWORKS,
            adapter.fetch().single().networks,
        )
    }

    @Test
    fun relayers_manifestWithoutName_displayNameFromComponentId() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        val bytes = signedManifestBytes(destinationManifest(name = null))
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(listOf(attributedEntry(digest = sha256Digest(bytes))), fetcher),
            fallback = relayersFallback(),
        )

        assertEquals("test-notary", adapter.fetch().single().name)
    }

    @Test
    fun relayers_noCatalogEntries_fallsBackToLegacy() = runTest {
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(emptyList(), FakeDiscoveryFetcher()),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    @Test
    fun relayers_digestMismatch_fallsBackToLegacy() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        val bytes = signedManifestBytes(destinationManifest())
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        // Pin the digest of *different* bytes — the review must reject.
        val wrongDigest = sha256Digest("tampered".toByteArray())
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(listOf(attributedEntry(digest = wrongDigest)), fetcher),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    @Test
    fun relayers_wrongSignature_fallsBackToLegacy() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        // Manifest names the operator key but is signed by a stranger.
        val bytes = signedManifestBytes(destinationManifest(), key = strangerPrivateKey)
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(listOf(attributedEntry(digest = sha256Digest(bytes))), fetcher),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    @Test
    fun relayers_operatorKeyDiffersFromCatalog_fallsBackToLegacy() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        // Self-consistent manifest (stranger key, stranger signature)
        // whose digest matches the pin — only the catalog↔manifest
        // operator-key equality check can catch it.
        val bytes = signedManifestBytes(
            destinationManifest(operator = strangerKey),
            key = strangerPrivateKey,
        )
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(
                listOf(attributedEntry(digest = sha256Digest(bytes), operator = operatorKey)),
                fetcher,
            ),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    @Test
    fun relayers_expiredManifest_fallsBackToLegacy() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        val bytes = signedManifestBytes(
            destinationManifest(validUntil = "2000-01-01T00:00:00Z"),
        )
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(listOf(attributedEntry(digest = sha256Digest(bytes))), fetcher),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    @Test
    fun relayers_failingEntrySkipped_survivorStillMapped() = runTest {
        val goodUri = "https://good.example.com/manifest.json"
        val badUri = "https://bad.example.com/manifest.json"
        val goodBytes = signedManifestBytes(destinationManifest())
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(goodUri, goodBytes)
            fail(badUri)
        }
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(
                listOf(
                    attributedEntry(
                        componentId = "onym:component:unreachable",
                        manifestUri = badUri,
                        digest = "sha256:" + "1".repeat(64),
                    ),
                    attributedEntry(manifestUri = goodUri, digest = sha256Digest(goodBytes)),
                ),
                fetcher,
            ),
            fallback = fallback,
        )

        val endpoints = adapter.fetch()

        assertEquals(listOf("https://relayer.example.com"), endpoints.map { it.url })
        assertEquals(0, fallback.fetchCallCount)
    }

    @Test
    fun relayers_noHttpsEndpoint_fallsBackToLegacy() = runTest {
        val uri = "https://operator.example.com/manifest.json"
        val bytes = signedManifestBytes(
            destinationManifest(endpointUris = listOf("wss://not-https.example.com")),
        )
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val fallback = relayersFallback()
        val adapter = DiscoveryBackedKnownRelayersFetcher(
            catalog = catalogOf(listOf(attributedEntry(digest = sha256Digest(bytes))), fetcher),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    // ─── Message-transport seat (Nostr relays) ────────────────────

    @Test
    fun nostr_mapsWssEndpointsAsDefault_skipsNonWss() = runTest {
        val wssUri = "https://courier.example.com/manifest.json"
        val httpsUri = "https://not-a-relay.example.com/manifest.json"
        val wssBytes = signedManifestBytes(
            destinationManifest(
                componentId = "onym:component:test-courier",
                seat = "courier",
                name = "Test Courier",
                endpointUris = listOf("https://api.example.com", "wss://relay.example.com"),
            ),
        )
        val httpsBytes = signedManifestBytes(
            destinationManifest(
                componentId = "onym:component:https-only",
                seat = "courier",
                endpointUris = listOf("https://only.example.com"),
            ),
        )
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(wssUri, wssBytes)
            respond(httpsUri, httpsBytes)
        }
        val fallback = RecordingNostrFallback(emptyList())
        val adapter = DiscoveryBackedKnownNostrRelaysFetcher(
            catalog = catalogOf(
                listOf(
                    attributedEntry(
                        componentId = "onym:component:test-courier",
                        seatType = "transport.message",
                        manifestUri = wssUri,
                        digest = sha256Digest(wssBytes),
                    ),
                    attributedEntry(
                        componentId = "onym:component:https-only",
                        seatType = "transport.message",
                        manifestUri = httpsUri,
                        digest = sha256Digest(httpsBytes),
                    ),
                ),
                fetcher,
            ),
            fallback = fallback,
        )

        val endpoints = adapter.fetch()

        assertEquals(
            listOf(
                NostrRelayEndpoint(
                    url = "wss://relay.example.com",
                    name = "Test Courier",
                    isDefault = true,
                ),
            ),
            endpoints,
        )
        assertEquals(0, fallback.fetchCallCount)
    }

    @Test
    fun nostr_noSurvivors_fallsBackToLegacy() = runTest {
        val fallback = RecordingNostrFallback(
            listOf(NostrRelayEndpoint(url = "wss://legacy.example.com", name = "Legacy")),
        )
        val adapter = DiscoveryBackedKnownNostrRelaysFetcher(
            catalog = catalogOf(emptyList(), FakeDiscoveryFetcher()),
            fallback = fallback,
        )

        assertEquals("wss://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    @Test
    fun nostr_noSurvivorsAndNullFallback_returnsEmpty() = runTest {
        val adapter = DiscoveryBackedKnownNostrRelaysFetcher(
            catalog = catalogOf(emptyList(), FakeDiscoveryFetcher()),
            fallback = null,
        )

        assertTrue(adapter.fetch().isEmpty())
    }

    // ─── Blob-storage seat (Blossom servers) ──────────────────────

    @Test
    fun blossom_mapsHttpsEndpointsAsDefault() = runTest {
        val uri = "https://blob.example.com/manifest.json"
        val bytes = signedManifestBytes(
            destinationManifest(
                componentId = "onym:component:test-blossom",
                seat = "blob.storage",
                name = "Test Blossom",
                endpointUris = listOf("https://blossom.example.com"),
            ),
        )
        val fetcher = FakeDiscoveryFetcher().apply { respond(uri, bytes) }
        val fallback = RecordingBlossomFallback(emptyList())
        val adapter = DiscoveryBackedKnownBlossomServersFetcher(
            catalog = catalogOf(
                listOf(
                    attributedEntry(
                        componentId = "onym:component:test-blossom",
                        seatType = "blob.storage",
                        manifestUri = uri,
                        digest = sha256Digest(bytes),
                    ),
                ),
                fetcher,
            ),
            fallback = fallback,
        )

        assertEquals(
            listOf(
                BlossomServerEndpoint(
                    url = "https://blossom.example.com",
                    name = "Test Blossom",
                    isDefault = true,
                ),
            ),
            adapter.fetch(),
        )
        assertEquals(0, fallback.fetchCallCount)
    }

    @Test
    fun blossom_noSurvivors_fallsBackToLegacy() = runTest {
        val fallback = RecordingBlossomFallback(
            listOf(BlossomServerEndpoint(url = "https://legacy.example.com", name = "Legacy")),
        )
        val adapter = DiscoveryBackedKnownBlossomServersFetcher(
            catalog = catalogOf(emptyList(), FakeDiscoveryFetcher()),
            fallback = fallback,
        )

        assertEquals("https://legacy.example.com", adapter.fetch().single().url)
        assertEquals(1, fallback.fetchCallCount)
    }

    // ─── Wrapping (identity without a catalog) ────────────────────

    @Test
    fun wrapping_nullCatalog_returnsLegacyFetcherUnwrapped() {
        val relayers = relayersFallback()
        val nostr = RecordingNostrFallback(emptyList())
        val blossom = RecordingBlossomFallback(emptyList())

        assertSame(relayers, DiscoveryBackedKnownRelayersFetcher.wrapping(relayers, catalog = null))
        assertSame(nostr, DiscoveryBackedKnownNostrRelaysFetcher.wrapping(nostr, catalog = null))
        assertSame(
            blossom,
            DiscoveryBackedKnownBlossomServersFetcher.wrapping(blossom, catalog = null),
        )
    }
}
