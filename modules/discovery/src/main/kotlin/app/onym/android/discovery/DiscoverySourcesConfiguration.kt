package app.onym.android.discovery

import kotlinx.serialization.Serializable

/**
 * One accepted snapshot's chain anchor, retained per catalog (§8):
 * enough to detect rollback and equivocation across refreshes without
 * keeping the full snapshot bytes.
 */
@Serializable
data class AcceptedCatalogSnapshot(
    /** `sha256:` digest of the accepted snapshot's exact bytes. */
    val digest: String,
    val sequence: Long,
    /** RFC 3339 acceptance timestamp (client clock). */
    val acceptedAt: String,
)

/**
 * One configured Discovery source. [pinnedOperatorKeyHex] is `null`
 * only for a source that has been added but whose TOFU fingerprint
 * confirmation is still outstanding — refresh skips unpinned sources,
 * so nothing is ever trusted before the user confirmed the key.
 */
@Serializable
data class DiscoverySource(
    /** Provider-manifest URL the source was added by. */
    val url: String,
    /** User-facing label (defaults to the manifest host at add time). */
    val label: String,
    /** `onym:component:` provider id from the verified manifest. */
    val providerId: String,
    /** 64-char lowercase hex Ed25519 operator key pinned at TOFU
     *  confirmation; a later manifest signed by a different key is
     *  rejected, never silently rotated. */
    val pinnedOperatorKeyHex: String? = null,
    /** Last accepted snapshot per catalogId — the §8 retention set. */
    val acceptedCatalogs: Map<String, AcceptedCatalogSnapshot> = emptyMap(),
)

/**
 * The persisted Discovery source set. Same wire-default convention as
 * :chain's `RelayerConfiguration`: [hasUserInteracted] defaults to
 * `true` so any *persisted* blob (which only exists once the user or
 * a default-seeding write touched the store) decodes as interacted;
 * only the in-memory [empty] cold-start value carries `false`.
 */
@Serializable
data class DiscoverySourcesConfiguration(
    val sources: List<DiscoverySource> = emptyList(),
    /** Default providers the user explicitly removed. A removed
     *  default source is never silently restored (§8) — bootstrap
     *  filters the shipped defaults against this set durably. */
    val removedDefaultProviderIds: Set<String> = emptySet(),
    val hasUserInteracted: Boolean = true,
) {
    companion object {
        /** Cold-start value: no sources, nothing removed,
         *  `hasUserInteracted = false` so bootstrap may seed the
         *  shipped default sources. */
        val empty: DiscoverySourcesConfiguration = DiscoverySourcesConfiguration(
            sources = emptyList(),
            removedDefaultProviderIds = emptySet(),
            hasUserInteracted = false,
        )
    }
}
