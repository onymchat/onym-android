package app.onym.android.foundation

import java.time.Instant

/**
 * Proof that this device may use one of a service's offers. Minimal
 * on purpose: the billing-backed provider that replaces the free
 * stub carries receipts elsewhere — consumers only need to know
 * *whether* and *until when*.
 *
 * Mirrors `ServiceEntitlement` in onym-ios OnymFoundation.
 */
data class ServiceEntitlement(
    val offerId: String,
    val componentId: String,
    /** `null` means no expiry (free tiers, lifetime purchases). */
    val expiresAt: Instant? = null,
)

/**
 * Gate on paid offers. The UI asks this before letting an offer be
 * selected; `null` means not entitled. Play Billing slots in behind
 * this seam later without touching consent or discovery.
 */
interface EntitlementProviding {
    suspend fun entitlement(offerId: String, componentId: String): ServiceEntitlement?
}

/**
 * The pre-billing stub: free offers are granted unconditionally and
 * without expiry, everything else — paid models, unknown models,
 * offers it cannot find — is refused.
 *
 * Whether an offer is free lives in the service's manifest, so the
 * provider takes a lookup function instead of duplicating that
 * state; the app supplies one over its pinned consents or catalog
 * entries.
 */
class FreeTierEntitlementProvider(
    private val offerLookup: suspend (offerId: String, componentId: String) -> ServiceOffer?,
) : EntitlementProviding {

    /** Convenience over the consent store: resolves offers from the
     *  active pinned manifest of the component in question. */
    constructor(consentStore: PinnedConsentStore) : this({ offerId, componentId ->
        consentStore.activeRecord(componentId)
            ?.consentedManifest()
            ?.offers
            ?.firstOrNull { it.offerId == offerId }
    })

    override suspend fun entitlement(offerId: String, componentId: String): ServiceEntitlement? {
        val offer = offerLookup(offerId, componentId) ?: return null
        if (!offer.isFree) return null
        return ServiceEntitlement(offerId = offerId, componentId = componentId, expiresAt = null)
    }
}
