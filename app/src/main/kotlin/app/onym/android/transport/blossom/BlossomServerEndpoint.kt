package app.onym.android.transport.blossom

import kotlinx.serialization.Serializable

/**
 * Configured Blossom media-server endpoint persisted in the device's
 * preferences. URL is stored as a String so any URI coercion lives at
 * use sites only.
 *
 * Blossom (BUD-01) servers speak plain HTTPS (`PUT /upload`,
 * `GET /<sha256>`), so the URL scheme here is `https://` (or `http://`
 * for local dev) — unlike the `wss://` Nostr relays. Mirrors
 * `NostrRelayEndpoint` and onym-ios `BlossomServerEndpoint.swift`.
 */
@Serializable
data class BlossomServerEndpoint(
    val url: String,
    val name: String,
    val isDefault: Boolean = false,
) {
    companion object {
        /** Onym-operated default server, seeded on first launch so
         *  media has somewhere to upload/download before the user
         *  configures anything. */
        val onymOfficial = BlossomServerEndpoint(
            url = "https://blossom.onym.app",
            name = "Onym Official",
            isDefault = true,
        )

        fun custom(url: String): BlossomServerEndpoint =
            BlossomServerEndpoint(url = url, name = "Custom", isDefault = false)
    }
}

/**
 * Persisted snapshot of the user's Blossom-server preferences.
 *
 * [hasUserInteracted] is the sticky bit that controls whether the
 * "first launch seed" applies on subsequent boots: once the user adds,
 * removes, or otherwise touches the list, we never re-seed — even if
 * they end up with an empty list. [resetToDefault] flips it back to
 * `false` so a deliberate reset re-enables seeding.
 *
 * Mirrors `NostrRelaysConfiguration` and onym-ios
 * `BlossomServersConfiguration.swift`.
 */
@Serializable
data class BlossomServersConfiguration(
    val endpoints: List<BlossomServerEndpoint> = emptyList(),
    val hasUserInteracted: Boolean = false,
) {
    companion object {
        val empty = BlossomServersConfiguration()
        val seed = BlossomServersConfiguration(
            endpoints = listOf(BlossomServerEndpoint.onymOfficial),
            hasUserInteracted = false,
        )
    }
}
