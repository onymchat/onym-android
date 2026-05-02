package chat.onym.android.chain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-resolver tests for [RelayerConfiguration.selectUrl]. The
 * three rules are load-bearing for the chain client; this suite
 * mirrors `RelayerConfigurationTests` from onym-ios PR #20 1:1.
 *
 * Uses `kotlin.random.Random(seed)` for deterministic random-mode
 * assertions (no `SeededRNG` fake needed — Kotlin's stdlib already
 * gives you a seedable LCG).
 */
class RelayerConfigurationTest {

    private val a = RelayerEndpoint("A", "https://a.example", "testnet")
    private val b = RelayerEndpoint("B", "https://b.example", "testnet")
    private val c = RelayerEndpoint("C", "https://c.example", "public")

    // ─── Empty list ───────────────────────────────────────────────

    @Test
    fun selectUrl_emptyEndpoints_returnsNull_underPrimary() {
        val cfg = RelayerConfiguration(strategy = RelayerStrategy.PRIMARY)
        assertNull(cfg.selectUrl())
    }

    @Test
    fun selectUrl_emptyEndpoints_returnsNull_underRandom() {
        val cfg = RelayerConfiguration(strategy = RelayerStrategy.RANDOM)
        assertNull(cfg.selectUrl(Random(42)))
    }

    // ─── PRIMARY rules ────────────────────────────────────────────

    @Test
    fun selectUrl_primary_returnsExplicitPrimary_whenSetAndStillInList() {
        val cfg = RelayerConfiguration(
            endpoints = listOf(a, b, c),
            primaryUrl = b.url,
            strategy = RelayerStrategy.PRIMARY,
        )
        assertEquals(b.url, cfg.selectUrl())
    }

    @Test
    fun selectUrl_primary_fallsBackToFirst_whenPrimaryUnset() {
        val cfg = RelayerConfiguration(
            endpoints = listOf(a, b),
            primaryUrl = null,
            strategy = RelayerStrategy.PRIMARY,
        )
        assertEquals(a.url, cfg.selectUrl())
    }

    @Test
    fun selectUrl_primary_fallsBackToFirst_whenPrimaryStale() {
        // Primary URL doesn't exist in endpoints → tolerated; fall
        // through to first. Lets the repository/UI be lazy about
        // clearing the marker on remove.
        val cfg = RelayerConfiguration(
            endpoints = listOf(a, b),
            primaryUrl = "https://no-longer-here.example",
            strategy = RelayerStrategy.PRIMARY,
        )
        assertEquals(a.url, cfg.selectUrl())
    }

    @Test
    fun selectUrl_primary_singleEndpoint_returnsThatEndpointEvenWithoutPromotion() {
        val cfg = RelayerConfiguration(
            endpoints = listOf(a),
            primaryUrl = null,
            strategy = RelayerStrategy.PRIMARY,
        )
        assertEquals(a.url, cfg.selectUrl())
    }

    // ─── RANDOM rules ─────────────────────────────────────────────

    @Test
    fun selectUrl_random_singleEndpoint_alwaysReturnsThatEndpoint() {
        val cfg = RelayerConfiguration(
            endpoints = listOf(a),
            primaryUrl = b.url,  // ignored under RANDOM
            strategy = RelayerStrategy.RANDOM,
        )
        repeat(10) { i -> assertEquals(a.url, cfg.selectUrl(Random(i.toLong()))) }
    }

    @Test
    fun selectUrl_random_visitsAllEndpointsOverManyCalls() {
        val cfg = RelayerConfiguration(
            endpoints = listOf(a, b, c),
            primaryUrl = a.url,  // RANDOM ignores primary
            strategy = RelayerStrategy.RANDOM,
        )
        val rng = Random(42)
        val visited = mutableSetOf<String>()
        // 1000 draws is plenty for 3-element uniform random; flake
        // probability is < 10^-100.
        repeat(1000) { visited.add(cfg.selectUrl(rng)!!) }
        assertEquals(setOf(a.url, b.url, c.url), visited)
    }

    @Test
    fun selectUrl_random_ignoresPrimaryMarker() {
        // Even with a perfectly valid primary, RANDOM doesn't favour
        // it — uniform draw across all three endpoints.
        val cfg = RelayerConfiguration(
            endpoints = listOf(a, b, c),
            primaryUrl = a.url,
            strategy = RelayerStrategy.RANDOM,
        )
        val rng = Random(7)
        // 30 draws against a fixed-seed RNG — at least one shouldn't
        // be `a.url` (probability of all-`a` is (1/3)^30 ≈ 10^-15).
        val draws = (0 until 30).map { cfg.selectUrl(rng) }
        assertTrue(
            "RANDOM should pick non-primary endpoints sometimes",
            draws.any { it != a.url },
        )
    }

    // ─── kotlinx.serialization round-trip ─────────────────────────

    @Test
    fun serialization_roundtripPreservesAllFields() {
        val original = RelayerConfiguration(
            endpoints = listOf(a, b, c),
            primaryUrl = b.url,
            strategy = RelayerStrategy.RANDOM,
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(RelayerConfiguration.serializer(), original)
        val decoded = json.decodeFromString(RelayerConfiguration.serializer(), encoded)
        assertEquals(original, decoded)
        assertNotNull(decoded.primaryUrl)
    }

    // ─── RelayerEndpoint.custom helper ────────────────────────────

    @Test
    fun custom_factory_setsNetworkAndDerivesNameFromHost() {
        val ep = RelayerEndpoint.custom("https://relayer.example.com:9443/path")
        assertEquals("custom", ep.network)
        assertEquals("relayer.example.com", ep.name)
        assertEquals("https://relayer.example.com:9443/path", ep.url)
    }

    // ─── PR #22 defaults + hasUserInteracted backward-compat ──────

    @Test
    fun empty_factory_returnsRandomStrategyAndUntouchedFlag() {
        val empty = RelayerConfiguration.empty
        assertTrue(empty.endpoints.isEmpty())
        assertNull(empty.primaryUrl)
        assertEquals(RelayerStrategy.RANDOM, empty.strategy)
        assertFalse(
            "fresh-install state must not be flagged as user-touched (would suppress auto-populate)",
            empty.hasUserInteracted,
        )
    }

    @Test
    fun defaultStrategy_isRandom_forCold_constructed_configuration() {
        // The data class default flipped from PRIMARY → RANDOM in
        // PR #22 to match the auto-populate behaviour: a fresh
        // install with the published list spread across endpoints
        // wants Random by default.
        val cfg = RelayerConfiguration()
        assertEquals(RelayerStrategy.RANDOM, cfg.strategy)
    }

    @Test
    fun serialization_roundtripPreservesHasUserInteracted() {
        val touched = RelayerConfiguration(
            endpoints = listOf(a),
            strategy = RelayerStrategy.PRIMARY,
            hasUserInteracted = true,
        )
        val untouched = RelayerConfiguration.empty

        val json = Json { encodeDefaults = true }
        for (original in listOf(touched, untouched)) {
            val encoded = json.encodeToString(RelayerConfiguration.serializer(), original)
            val decoded = json.decodeFromString(RelayerConfiguration.serializer(), encoded)
            assertEquals(original, decoded)
            assertEquals(original.hasUserInteracted, decoded.hasUserInteracted)
        }
    }

    @Test
    fun serialization_decodingPR20WireShape_yieldsHasUserInteractedTrue() {
        // PR #20 saved configurations without `hasUserInteracted`.
        // Backward-compat default is `true` so already-configured
        // users don't lose their custom URLs to auto-populate.
        val pr20Json = """
            {
              "endpoints": [
                { "name": "Onym Testnet", "url": "https://relayer-testnet.onym.chat", "network": "testnet" }
              ],
              "primaryUrl": "https://relayer-testnet.onym.chat",
              "strategy": "PRIMARY"
            }
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(RelayerConfiguration.serializer(), pr20Json)
        assertTrue(
            "PR #20 saves must decode as `hasUserInteracted = true` so refresh() doesn't blow them away",
            decoded.hasUserInteracted,
        )
    }
}
