package app.onym.android.transport.nostr

import kotlinx.serialization.Serializable

/**
 * Configured Nostr relay endpoint persisted in the device's
 * preferences. URL is stored as a String so the [java.net.URI] /
 * [java.net.URL] coercion lives at use sites only.
 *
 * Mirrors `NostrRelayEndpoint.swift` from onym-ios PR #87.
 */
@Serializable
data class NostrRelayEndpoint(
    val url: String,
    val name: String,
    val isDefault: Boolean = false,
) {
    companion object {
        /** Onym-operated default relay, seeded on first launch so the
         *  inbox transport has somewhere to connect before the user
         *  configures anything. */
        val onymOfficial = NostrRelayEndpoint(
            url = "wss://nostr.onym.chat",
            name = "Onym Official",
            isDefault = true,
        )

        fun custom(url: String): NostrRelayEndpoint =
            NostrRelayEndpoint(url = url, name = "Custom", isDefault = false)
    }
}

/**
 * Persisted snapshot of the user's relay preferences.
 *
 * [hasUserInteracted] is the sticky bit that controls whether the
 * "first launch seed" applies on subsequent boots: once the user
 * adds, removes, or otherwise touches the list, we never re-seed
 * — even if they end up with an empty list. [resetToDefault] flips
 * it back to `false` so a deliberate reset re-enables seeding.
 *
 * Mirrors `NostrRelaysConfiguration.swift` from onym-ios PR #87.
 */
@Serializable
data class NostrRelaysConfiguration(
    val endpoints: List<NostrRelayEndpoint> = emptyList(),
    val hasUserInteracted: Boolean = false,
) {
    companion object {
        val empty = NostrRelaysConfiguration()
        val seed = NostrRelaysConfiguration(
            endpoints = listOf(NostrRelayEndpoint.onymOfficial),
            hasUserInteracted = false,
        )
    }
}
