package app.onym.android.chain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format pin for [RelayerEndpoint]'s custom serializer. The
 * serializer is the bridge between three on-disk shapes:
 *
 *  1. **New plural** — `"networks": ["testnet", "public"]`. The
 *     current published `relayers.json` and every save going forward.
 *  2. **Legacy singular** — `"network": "testnet"`. PR #20 / PR #22
 *     saves on disk; one-time migration via this decoder.
 *  3. **Mixed** — defensive: if both fields are present, the plural
 *     wins (the future shape).
 *
 * Encode side: always writes the new plural shape; the legacy field
 * is never serialized. Pinned here so a future "tidy the surrogate"
 * refactor doesn't silently re-emit the singular field.
 *
 * Mirrors `RelayerEndpointSchemaTests` from onym-ios PR #23.
 */
class RelayerEndpointSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decode_pluralWireShape_yieldsNetworksList() {
        val raw = """
            { "name": "Onym Mainnet",
              "url": "https://relayer.onym.chat",
              "networks": ["testnet", "public"] }
        """.trimIndent()
        val ep = json.decodeFromString(RelayerEndpoint.serializer(), raw)
        assertEquals(listOf("testnet", "public"), ep.networks)
        assertEquals("Onym Mainnet", ep.name)
        assertEquals("https://relayer.onym.chat", ep.url)
    }

    @Test
    fun decode_legacySingularNetwork_promotesToOneElementList() {
        // PR #20 saved configurations look like this on disk —
        // backward-compat is load-bearing for the silent install
        // upgrade path.
        val raw = """
            { "name": "Onym Testnet",
              "url": "https://relayer-testnet.onym.chat",
              "network": "testnet" }
        """.trimIndent()
        val ep = json.decodeFromString(RelayerEndpoint.serializer(), raw)
        assertEquals(listOf("testnet"), ep.networks)
    }

    @Test
    fun decode_bothFieldsPresent_pluralWins() {
        // Defensive: if a future asset publishes both shapes during a
        // transition, the plural is the canonical answer.
        val raw = """
            { "name": "Onym",
              "url": "https://relayer.example",
              "networks": ["testnet"],
              "network": "public" }
        """.trimIndent()
        val ep = json.decodeFromString(RelayerEndpoint.serializer(), raw)
        assertEquals(listOf("testnet"), ep.networks)
    }

    @Test
    fun decode_neitherFieldPresent_yieldsEmptyList() {
        // A published-but-unspecified endpoint — would render with no
        // network badges. Keep decode lenient so a single bad row
        // doesn't fail the whole document.
        val raw = """
            { "name": "Onym",
              "url": "https://relayer.example" }
        """.trimIndent()
        val ep = json.decodeFromString(RelayerEndpoint.serializer(), raw)
        assertTrue(ep.networks.isEmpty())
    }

    @Test
    fun encode_alwaysEmitsPlural_neverSingular() {
        // Encoder pin: never write the legacy `network` field. A
        // future refactor that "tidies up" the surrogate must keep
        // this property — emitting the singular field would round-
        // trip back to a one-element list, silently losing data when
        // the source had multiple networks.
        val ep = RelayerEndpoint(
            name = "Onym",
            url = "https://relayer.example",
            networks = listOf("testnet", "public"),
        )
        val encoded = json.encodeToString(RelayerEndpoint.serializer(), ep)
        assertTrue(
            "encoded output must contain `networks` array, was: $encoded",
            encoded.contains("\"networks\":[\"testnet\",\"public\"]"),
        )
        assertFalse(
            "encoded output must not contain legacy `network` field, was: $encoded",
            encoded.contains("\"network\":"),
        )
    }

    @Test
    fun roundTrip_preservesNetworksList() {
        val original = RelayerEndpoint(
            name = "Onym",
            url = "https://relayer.example",
            networks = listOf("testnet", "public", "futurenet"),
        )
        val encoded = json.encodeToString(RelayerEndpoint.serializer(), original)
        val decoded = json.decodeFromString(RelayerEndpoint.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_publishedRelayersJsonShape_matchesProductionWire() {
        // Snapshot of the live published `relayers.json` shape (as of
        // PR #23). Pins us against the on-disk shape the
        // `releases/latest/download/<asset>` redirect actually emits
        // — the entire deployment hot-path. If this test goes red, a
        // server-side schema change has shipped that we need to
        // mirror in the decoder.
        val live = """
            {
              "version": 1,
              "relayers": [
                { "name": "Onym Official",
                  "url": "https://relayer.onym.chat",
                  "networks": ["testnet", "public"] }
              ]
            }
        """.trimIndent()
        val doc = json.decodeFromString(KnownRelayersDocument.serializer(), live)
        assertEquals(1, doc.version)
        assertEquals(1, doc.relayers.size)
        assertEquals(listOf("testnet", "public"), doc.relayers.single().networks)
    }
}
