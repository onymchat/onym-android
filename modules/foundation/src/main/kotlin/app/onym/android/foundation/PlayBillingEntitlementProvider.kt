package app.onym.android.foundation

import java.time.Instant

/**
 * Composes with [FreeTierEntitlementProvider] exactly as iOS composed
 * `StoreKitEntitlementProvider` over its free-tier provider: free
 * offers resolve with zero network; paid offers require a
 * [ChannelOfferCatalog] entry (a real Play product) AND a currently
 * valid, previously verified-and-stored [SeatEntitlement] — anything
 * else resolves to "not entitled" rather than throwing, so a seat
 * picker can treat every offer uniformly.
 *
 * This provider never verifies or purchases anything itself — it only
 * answers "is this device currently entitled." Verification happens
 * once, at redemption time, in [SeatPurchaseFlow]; this provider
 * trusts what's already in [entitlementStore].
 *
 * Mirrors the `StoreKitEntitlementProvider` + `FreeTierEntitlementProvider`
 * composition in onym-ios OnymBilling/OnymFoundation.
 */
class PlayBillingEntitlementProvider(
    private val entitlementStore: SeatEntitlementStore,
    private val freeTier: FreeTierEntitlementProvider,
    private val catalog: suspend () -> ChannelOfferCatalog,
) : EntitlementProviding {

    override suspend fun entitlement(offerId: String, componentId: String): ServiceEntitlement? {
        freeTier.entitlement(offerId, componentId)?.let { return it }

        // No catalog entry means this isn't a real, sellable Play
        // product yet (the catalog ships empty until one exists) —
        // "not purchasable through this app," not an error.
        catalog().offerFor(componentId, offerId) ?: return null

        val stored = entitlementStore.get(componentId, offerId) ?: return null
        if (!Instant.now().isBefore(stored.expiresAt)) return null

        return ServiceEntitlement(offerId = offerId, componentId = componentId, expiresAt = stored.expiresAt)
    }
}
