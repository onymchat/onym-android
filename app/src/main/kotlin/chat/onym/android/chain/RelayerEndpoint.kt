package chat.onym.android.chain

import androidx.annotation.StringRes
import chat.onym.android.R
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URI
import java.net.URISyntaxException
import kotlin.random.Random

/**
 * One published relayer endpoint. Wire format:
 *
 * ```json
 * { "name": "Onym Official",
 *   "url": "https://relayer.onym.chat",
 *   "networks": ["testnet", "public"] }
 * ```
 *
 * The `networks` field is a list because a single deployment can
 * serve multiple Stellar networks (this is what the published
 * `relayers.json` actually emits).
 *
 * **Backward-compat with PR #20 / PR #22 saves.** Old saved
 * configurations used a singular `network: String` field. The
 * custom [RelayerEndpointSerializer] decodes both shapes — singular
 * is promoted to a one-element list at decode time. Encoder always
 * emits the plural shape, so a future load reads only the new
 * format.
 *
 * The "custom" endpoint type (user-typed URLs not in the published
 * list) carries `networks = listOf("custom")` — a sentinel the UI
 * uses to colour the network badge differently.
 *
 * Mirrors `RelayerEndpoint` from onym-ios PR #18 / PR #20 / PR #23.
 */
@Serializable(with = RelayerEndpointSerializer::class)
data class RelayerEndpoint(
    val name: String,
    val url: String,
    val networks: List<String>,
) {
    companion object {
        /** Sentinel network tag for user-typed URLs. */
        const val CUSTOM_NETWORK = "custom"

        /**
         * Build a "custom" endpoint from a user-typed URL. Networks
         * is the single-element `[CUSTOM_NETWORK]` sentinel; name
         * defaults to the URL host (or the raw URL if the host
         * can't be parsed — defensive fallback; the ViewModel
         * rejects malformed URLs upstream).
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
                networks = listOf(CUSTOM_NETWORK),
            )
        }
    }
}

/**
 * Hand-rolled serializer that reads both the new `networks: [...]`
 * shape (the current published format + every save going forward)
 * AND the legacy singular `network: "..."` shape (PR #20 / PR #22
 * saved configurations on disk). Encoder always emits the plural
 * shape; the legacy field is never written by this version.
 *
 * Surrogate trick: declare an internal `@Serializable` data class
 * with both fields nullable, decode through it, then merge into
 * the typed [RelayerEndpoint]. Avoids hand-rolling the decoder
 * structurally — kotlinx.serialization handles the JSON traversal
 * and missing-field defaults.
 *
 * Pinned by `RelayerEndpointSchemaTest`.
 */
internal object RelayerEndpointSerializer : KSerializer<RelayerEndpoint> {

    @Serializable
    internal data class Surrogate(
        val name: String,
        val url: String,
        // Plural — the new wire shape. Optional so legacy decode doesn't fail.
        val networks: List<String>? = null,
        // Legacy singular — read on decode to backfill `networks`,
        // never written on encode.
        val network: String? = null,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): RelayerEndpoint {
        val s = Surrogate.serializer().deserialize(decoder)
        // Prefer plural; fall back to promoting singular; otherwise
        // empty list (a published-but-unspecified endpoint — would
        // render with no badges).
        val networks = s.networks ?: listOfNotNull(s.network)
        return RelayerEndpoint(name = s.name, url = s.url, networks = networks)
    }

    override fun serialize(encoder: Encoder, value: RelayerEndpoint) {
        Surrogate.serializer().serialize(
            encoder,
            Surrogate(
                name = value.name,
                url = value.url,
                networks = value.networks,
                // Always null — never write the legacy singular field.
                network = null,
            ),
        )
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
 * Status of the most recent (or in-flight) fetch of the published
 * relayers list. Drives the UI's "Add from Published List" gating
 * — the picker shows a spinner / error / empty-state copy depending
 * on this enum, replacing the PR #20 logic that gated everything
 * on `knownList.isEmpty` (which spun forever after a failed fetch).
 *
 * Mirrors `RelayerFetchStatus` from onym-ios PR #23.
 */
sealed interface RelayerFetchStatus {
    /** No fetch has been attempted yet. UI shows the spinner. */
    data object Idle : RelayerFetchStatus

    /** A fetch is in flight. UI shows the spinner if the cached
     *  list is empty, or the cached list (with optional staleness
     *  badge later) if it's not. */
    data object Fetching : RelayerFetchStatus

    /** Last fetch succeeded. UI shows the published list, or a
     *  "no published relayers yet" message if the list is empty. */
    data object Success : RelayerFetchStatus

    /** Last fetch failed. [message] is a localised user-facing
     *  string. UI surfaces it with a Try Again button. */
    data class Failed(val message: String) : RelayerFetchStatus
}

/**
 * Sealed taxonomy of fetch failures, raised by the
 * [KnownRelayersFetcher] implementation when something goes wrong
 * before the typed [RelayerEndpoint] list lands. Used by the
 * repository to map to a localised user-facing message.
 *
 * Mirrors `KnownRelayersFetchError` from onym-ios PR #23.
 */
sealed class RelayersFetchError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Server returned a non-2xx response. */
    class BadStatus(val code: Int) : RelayersFetchError("server returned HTTP $code")

    /** JSON decoding of the response body failed (schema drift). */
    class MalformedDocument(cause: Throwable) :
        RelayersFetchError("response body didn't match the expected schema", cause)
}

/**
 * Snapshot the [RelayerRepository] publishes through its StateFlow.
 * Three pieces of state:
 *
 *  - [knownRelayers] — last successful fetch (or empty if never
 *    fetched / cleared). Cached to disk so the picker has something
 *    to show before the next start() / refresh() completes.
 *  - [configuration] — the user's multi-endpoint config + strategy.
 *  - [fetchStatus] — drives the "Add from Published List" UI gate.
 */
data class RelayerState(
    val knownRelayers: List<RelayerEndpoint>,
    val configuration: RelayerConfiguration,
    val fetchStatus: RelayerFetchStatus,
) {
    /** Convenience pass-through to [RelayerConfiguration.selectUrl]. */
    fun selectUrl(random: Random = Random.Default): String? = configuration.selectUrl(random)

    companion object {
        /** Cold-start in-memory state until the repository's
         *  bootstrap reads from disk + start kicks off a fetch. */
        val empty: RelayerState = RelayerState(
            knownRelayers = emptyList(),
            configuration = RelayerConfiguration.empty,
            fetchStatus = RelayerFetchStatus.Idle,
        )
    }
}
