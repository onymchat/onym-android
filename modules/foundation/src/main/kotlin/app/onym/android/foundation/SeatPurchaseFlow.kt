package app.onym.android.foundation

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/** What the broker needs to issue a seat entitlement for one
 *  completed purchase. [purchaseToken] is Play Billing's evidence
 *  (the Android analogue of a StoreKit `jwsRepresentation`); the
 *  broker validates it against Play server-side — this client never
 *  parses or trusts it directly. */
data class SeatEntitlementRequest(
    val offerId: String,
    /** `onym:component:<operator>` — the seat this credential is for. */
    val audience: String,
    /** `onym:seat-key:<64 lowercase hex>` — the device's seat key for
     *  this operator. */
    val subject: String,
    /** The seat's X25519 agreement public key — the broker seals the
     *  issued entitlement to this, so it travels to one seat and is
     *  readable by one device. */
    val sealToAgreementPublicKey: ByteArray,
    val purchaseToken: String,
)

/**
 * What a billing broker exposes — deliberately narrow. No
 * per-entitlement status/check endpoint: see [SeatEntitlement]'s doc
 * comment for why that's a protocol requirement, not an oversight.
 *
 * Mirrors `BillingBrokerClient` in onym-ios OnymBilling.
 */
interface BillingBrokerClient {
    /** Returns the sealed-entitlement envelope as JSON (see
     *  [SeatSealedEnvelope.toJson]/[SeatSealedEnvelope.fromJson]). */
    suspend fun issueEntitlement(request: SeatEntitlementRequest): String
    suspend fun refreshEntitlement(entitlementId: String, sealToAgreementPublicKey: ByteArray): String
    suspend fun revocationEpoch(): RevocationEpoch
}

/** One completed purchase, ready to redeem. */
data class SeatPurchaseRequest(
    val offer: ChannelOffer,
    val purchaseToken: String,
    val subject: String,
    val sealToAgreementPublicKey: ByteArray,
)

/**
 * Redeems a completed Play Billing purchase into a verified, stored
 * [SeatEntitlement]. Ordering is load-bearing: the entitlement is
 * verified and stored **before** the caller acknowledges the Play
 * purchase (see the app-side purchase observer) — acknowledging first
 * would let a broker outage silently lose a paid purchase, since Play
 * only redelivers *unacknowledged* purchases.
 *
 * Mirrors `SeatPurchaseFlow` in onym-ios OnymBilling.
 */
class SeatPurchaseFlow(
    private val broker: BillingBrokerClient,
    private val entitlementStore: SeatEntitlementStore,
    /** Pinned per-operator from ITS VERIFIED MANIFEST — never from a
     *  presented credential (a credential must never nominate its own
     *  authority). */
    private val trustedIssuers: suspend (componentId: String) -> Set<String>,
    private val issuerPublicKeyHex: suspend (issuer: String) -> String?,
    private val agreementPrivateKey: suspend (componentId: String) -> X25519PrivateKeyParameters,
    private val revokedEntitlementIds: suspend (componentId: String) -> Set<String>,
) {
    suspend fun redeem(request: SeatPurchaseRequest): SeatEntitlement {
        val sealedJson = broker.issueEntitlement(
            SeatEntitlementRequest(
                offerId = request.offer.offerId,
                audience = request.offer.componentId,
                subject = request.subject,
                sealToAgreementPublicKey = request.sealToAgreementPublicKey,
                purchaseToken = request.purchaseToken,
            ),
        )

        val trusted = trustedIssuers(request.offer.componentId)
        val issuerKeysHex = trusted.associateWith { issuerPublicKeyHex(it) }.filterValues { it != null }
        val senderKeys = issuerKeysHex.values.filterNotNull()
            .mapNotNull { hex -> ServiceManifestFormat.bytesFromLowercaseHex(hex) }
            .map { Ed25519PublicKeyParameters(it, 0) }
            .toSet()

        val envelope = SeatSealedEnvelope.fromJson(sealedJson)
        val payload = SeatSealedEnvelope.open(
            envelope,
            agreementPrivateKey(request.offer.componentId),
            senderKeys,
        )
        val entitlement = SeatEntitlement.decode(payload)

        val revoked = revokedEntitlementIds(request.offer.componentId)
        SeatEntitlementVerifier.verify(
            entitlement = entitlement,
            trustedIssuers = trusted,
            issuerPublicKeyHex = { issuer -> issuerKeysHex[issuer] },
            audience = request.offer.componentId,
            subject = request.subject,
            revokedEntitlementIds = revoked,
        )

        entitlementStore.put(entitlement)
        return entitlement
    }
}
