package app.onym.android.foundation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The client's local, trimmed mirror of the full onym-system
 * `settlement/ChannelOffer.md` document — just what the consent
 * surface needs to display and what [PlayBillingEntitlementProvider]
 * needs to map a Play product id to an operator offer. The full
 * settlement document (commission math, payout cadence, dispute
 * forum, ...) lives server-side; the client never needs it.
 *
 * `operatorShareBps` + `frontendCommissionBps` must sum to `10000` —
 * "a commission a person cannot see is not disclosed" is the
 * consent-surface rule this pair exists to satisfy.
 *
 * Mirrors `ChannelOffer` (client struct) in onym-ios OnymBilling.
 */
@Serializable
data class ChannelOffer(
    val channelOfferId: String,
    val componentId: String,
    val offerId: String,
    /** The Play Billing product id this offer maps to. */
    val productId: String,
    val productType: String,
    val operatorShareBps: Int,
    val frontendCommissionBps: Int,
) {
    init {
        require(operatorShareBps + frontendCommissionBps == 10_000) {
            "operatorShareBps + frontendCommissionBps must sum to 10000, got " +
                "${operatorShareBps + frontendCommissionBps}"
        }
    }
}

/**
 * Bundled JSON resource mapping Play product ids to operator offers.
 * Ships **empty** until a real product exists — same posture as iOS's
 * `ChannelOffers.json`. Duplicate `productId`s are dropped at
 * construction (never crash; the later entry simply becomes
 * unsellable) rather than treated as a fatal catalog error.
 */
class ChannelOfferCatalog private constructor(private val byProductId: Map<String, ChannelOffer>) {

    fun offerFor(productId: String): ChannelOffer? = byProductId[productId]

    fun offerFor(componentId: String, offerId: String): ChannelOffer? =
        byProductId.values.firstOrNull { it.componentId == componentId && it.offerId == offerId }

    val productIds: Set<String> get() = byProductId.keys

    companion object {
        val EMPTY = ChannelOfferCatalog(emptyMap())

        fun decode(json: String): ChannelOfferCatalog {
            val offers = try {
                Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChannelOffer.serializer()), json)
            } catch (_: Exception) {
                return EMPTY
            }
            val byProductId = LinkedHashMap<String, ChannelOffer>()
            for (offer in offers) {
                byProductId.putIfAbsent(offer.productId, offer)
            }
            return ChannelOfferCatalog(byProductId)
        }
    }
}
