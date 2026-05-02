package chat.onym.android.chain

import androidx.annotation.StringRes
import chat.onym.android.R
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException
import kotlin.random.Random

/**
 * One published relayer endpoint. Wire format pinned by
 * [KnownRelayersDocument] — interop with the iOS client + the
 * server-side `relayers.json` artifact rides on the JSON field
 * names being exactly `name` / `url` / `network`.
 *
 * The `network` field is `"testnet"` / `"public"` for published
 * relayers and `"custom"` for user-typed URLs (synthesised via
 * [RelayerEndpoint.Companion.custom]).
 *
 * Mirrors `RelayerEndpoint` from onym-ios PR #18 / PR #20.
 */
@Serializable
data class RelayerEndpoint(
    val name: String,
    val url: String,
    val network: String,
) {
    companion object {
        /**
         * Build a "custom" endpoint from a user-typed URL. Network
         * tag is fixed at `"custom"`; name defaults to the URL host
         * (or the raw URL if the host can't be parsed — defensive
         * fallback; the ViewModel rejects malformed URLs upstream).
         */
        fun custom(url: String): RelayerEndpoint {
            val host = try {
                URI(url).host
            } catch (_: URISyntaxException) {
                null
            }
            return RelayerEndpoint(
                name = host ?: url,
                url = url,
                network = "custom",
            )
        }
    }
}

/**
 * Top-level shape of `relayers.json`, fetched from the latest
 * GitHub Release of `onymchat/onym-relayer`. `version = 1` today.
 */
@Serializable
data class KnownRelayersDocument(
    val version: Int,
    val relayers: List<RelayerEndpoint>,
)

/**
 * How [RelayerConfiguration.selectUrl] picks one endpoint per
 * request from a multi-endpoint configuration.
 *
 * Mirrors `RelayerStrategy` from onym-ios PR #20.
 */
enum class RelayerStrategy(@StringRes val displayNameResId: Int) {
    /** Always use [RelayerConfiguration.primaryUrl] when set; fall
     *  back to the first endpoint when the primary marker is unset
     *  or stale. */
    PRIMARY(R.string.relayer_strategy_primary),

    /** Uniformly random per request. [RelayerConfiguration.primaryUrl]
     *  is irrelevant in this mode. */
    RANDOM(R.string.relayer_strategy_random),
}

/**
 * Multi-endpoint relayer configuration the chain client reads per
 * request. The user adds endpoints (mix of known + custom),
 * optionally marks one as primary, and picks a strategy. Selection
 * happens entirely in [selectUrl] — pure function, no I/O,
 * deterministic given the supplied [Random].
 *
 * Wire format is `@Serializable` so the persistence seam can
 * round-trip the whole configuration as a single JSON blob (matches
 * the iOS twin's UserDefaults blob).
 *
 * Mirrors `RelayerConfiguration` from onym-ios PR #20.
 */
@Serializable
data class RelayerConfiguration(
    val endpoints: List<RelayerEndpoint> = emptyList(),
    /** URL of the primary endpoint when [strategy] is
     *  [RelayerStrategy.PRIMARY]. May be `null` (no primary marked
     *  yet) or stale (primary endpoint was removed but caller
     *  forgot to clear the marker — [selectUrl] tolerates this). */
    val primaryUrl: String? = null,
    /** Default flipped to [RelayerStrategy.RANDOM] in PR #22 — for
     *  a fresh install with the auto-populated published list, the
     *  random strategy spreads requests evenly across the deployed
     *  relayers. Users who want a single endpoint just delete the
     *  rest + flip to PRIMARY. */
    val strategy: RelayerStrategy = RelayerStrategy.RANDOM,
    /**
     * `true` once the user has explicitly added/removed an endpoint,
     * marked a primary, or switched the strategy. Distinguishes
     * "the user has never touched this" (auto-populate eligible on
     * the next [chat.onym.android.chain.RelayerRepository.refresh])
     * from "the user explicitly cleared the list" (don't auto-
     * repopulate).
     *
     * **Backward-compat with PR #20 saves**: kotlinx.serialization
     * fills missing fields with the default. The default below is
     * `true` so a PR #20 wire shape (no `hasUserInteracted` field)
     * decodes as "interacted" — old users with custom URLs don't
     * get them blown away by re-population from the manifest.
     * Cold-fresh installs explicitly construct
     * [RelayerConfiguration.empty] which sets `false`.
     */
    val hasUserInteracted: Boolean = true,
) {
    /**
     * Resolve the URL for a single chain request. Pure function;
     * the three rules:
     *
     *  1. Empty endpoints → `null` regardless of strategy. The
     *     chain client refuses to publish.
     *  2. [RelayerStrategy.PRIMARY]:
     *     - Return [primaryUrl] if set AND still in [endpoints].
     *     - Otherwise fall back to `endpoints.first().url`. Tolerates
     *       stale primary markers + lets a single-endpoint user
     *       skip the "promote to primary" tap.
     *  3. [RelayerStrategy.RANDOM]: uniform random over [endpoints]
     *     using [random]. [primaryUrl] is irrelevant.
     *
     * @param random Injected so tests can pin behaviour with a
     *        seeded [Random]. Production callers pass the default.
     */
    fun selectUrl(random: Random = Random.Default): String? {
        if (endpoints.isEmpty()) return null
        return when (strategy) {
            RelayerStrategy.PRIMARY -> {
                val primary = primaryUrl
                if (primary != null && endpoints.any { it.url == primary }) {
                    primary
                } else {
                    endpoints.first().url
                }
            }
            RelayerStrategy.RANDOM -> endpoints.random(random).url
        }
    }

    companion object {
        /** Cold-install starting state: no endpoints, no primary,
         *  RANDOM strategy, **`hasUserInteracted = false`** so the
         *  next [chat.onym.android.chain.RelayerRepository.refresh]
         *  fans the published list into [endpoints]. */
        val empty: RelayerConfiguration = RelayerConfiguration(
            endpoints = emptyList(),
            primaryUrl = null,
            strategy = RelayerStrategy.RANDOM,
            hasUserInteracted = false,
        )
    }
}

/**
 * Snapshot the [RelayerRepository] publishes through its StateFlow.
 * Two parallel pieces of state:
 *
 *  - [knownRelayers] — last successful fetch (or empty if never
 *    fetched / cleared). Cached to disk so the picker has something
 *    to show before the next start() / refresh() completes.
 *  - [configuration] — the user's multi-endpoint config + strategy.
 */
data class RelayerState(
    val knownRelayers: List<RelayerEndpoint>,
    val configuration: RelayerConfiguration,
) {
    /** Convenience pass-through to [RelayerConfiguration.selectUrl]. */
    fun selectUrl(random: Random = Random.Default): String? = configuration.selectUrl(random)
}
