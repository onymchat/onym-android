package chat.onym.android.chain

import kotlinx.serialization.Serializable

/**
 * One published relayer endpoint. Wire format pinned by
 * [KnownRelayersDocument] — interop with the iOS client + the
 * server-side `relayers.json` artifact rides on the JSON field
 * names being exactly `name` / `url` / `network`.
 *
 * Mirrors `RelayerEndpoint` from onym-ios PR #18.
 */
@Serializable
data class RelayerEndpoint(
    val name: String,
    val url: String,
    val network: String,
)

/**
 * Top-level shape of `relayers.json`, fetched from the latest
 * GitHub Release of `onymchat/onym-relayer`. `version = 1` today;
 * future schema changes bump the version and we'd add a converter
 * (forwards-compat is best-effort — `Json { ignoreUnknownKeys =
 * true }` already swallows extra fields).
 */
@Serializable
data class KnownRelayersDocument(
    val version: Int,
    val relayers: List<RelayerEndpoint>,
)

/**
 * What the user picked. Two cases:
 *
 *  - [Known] — one of the published [RelayerEndpoint]s the fetcher
 *    discovered. The picker UI surfaces these as a list.
 *  - [Custom] — a user-typed URL. Used for private deployments,
 *    localhost dev, sideloaded networks. URL is opaque to us; we
 *    only validate that it parses + uses http/https + has a host
 *    (see `RelayerPickerViewModel.validate`).
 *
 * `null` selection means "user hasn't picked yet" — chain code
 * should refuse to publish until the user picks.
 *
 * Mirrors `RelayerSelection` from onym-ios PR #18.
 */
sealed class RelayerSelection {
    abstract val url: String

    data class Known(val endpoint: RelayerEndpoint) : RelayerSelection() {
        override val url: String get() = endpoint.url
    }

    data class Custom(override val url: String) : RelayerSelection()
}

/**
 * Snapshot the [RelayerRepository] publishes through its StateFlow.
 * Two parallel pieces of state:
 *
 *  - [knownRelayers] — last successful fetch (or empty if never
 *    fetched / cleared). Cached to disk so the picker has something
 *    to show before the next start() / refresh() completes.
 *  - [selection] — user's current pick.
 */
data class RelayerState(
    val knownRelayers: List<RelayerEndpoint>,
    val selection: RelayerSelection?,
)
