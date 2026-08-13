@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.foundation

import app.onym.android.foundation.ServiceManifestTestSigning.signedManifestBytes
import app.onym.android.foundation.ServiceManifestTestSigning.unsignedManifest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The pre-billing entitlement stub: free offers granted without
 * expiry, everything else refused.
 */
class FreeTierEntitlementProviderTest {

    private val component = "onym:component:test-courier"

    private fun offers(vararg pairs: Pair<String, String>) =
        pairs.map { (id, model) -> ServiceOffer(offerId = id, model = model) }
            .associateBy { it.offerId }

    private fun provider(known: Map<String, ServiceOffer>) =
        FreeTierEntitlementProvider { offerId, componentId ->
            if (componentId == component) known[offerId] else null
        }

    @Test
    fun freeOfferIsGrantedWithoutExpiry() = runTest {
        val provider = provider(offers("free-tier" to "free"))
        val entitlement = provider.entitlement("free-tier", component)
        assertEquals(
            ServiceEntitlement(offerId = "free-tier", componentId = component, expiresAt = null),
            entitlement,
        )
    }

    @Test
    fun paidOfferIsRefused() = runTest {
        val provider = provider(offers("monthly" to "subscription", "pack" to "consumable"))
        assertNull(provider.entitlement("monthly", component))
        assertNull(provider.entitlement("pack", component))
    }

    @Test
    fun unknownModelIsRefused() = runTest {
        val provider = provider(offers("mystery" to "lifetime-nft"))
        assertNull(provider.entitlement("mystery", component))
    }

    @Test
    fun unknownOfferIsRefused() = runTest {
        val provider = provider(offers("free-tier" to "free"))
        assertNull(provider.entitlement("does-not-exist", component))
        assertNull(provider.entitlement("free-tier", "onym:component:other"))
    }

    @Test
    fun consentStoreConvenienceResolvesFromActivePinnedManifest() = runTest {
        val store = InMemoryConsentStore()
        val reviewed = ServiceManifestReviewer().review(
            signedManifestBytes(
                unsignedManifest(
                    offers = listOf(
                        buildJsonObject {
                            put("offerId", "free-tier")
                            put("model", "free")
                        },
                        buildJsonObject {
                            put("offerId", "monthly")
                            put("model", "subscription")
                            put("period", "monthly")
                        },
                    ),
                ),
            ),
            now = Instant.parse("2026-08-13T12:00:00Z"),
        )
        store.accept(reviewed, acceptedAt = Instant.parse("2026-08-13T12:00:00Z"))

        val provider = FreeTierEntitlementProvider(store)
        assertEquals(
            ServiceEntitlement("free-tier", component, expiresAt = null),
            provider.entitlement("free-tier", component),
        )
        // Paid offer from the same consented manifest still refuses.
        assertNull(provider.entitlement("monthly", component))
        // No consent for the component at all → refused.
        assertNull(provider.entitlement("free-tier", "onym:component:unconsented"))
    }

    /** Minimal in-memory [PinnedConsentStore] — the DataStore-backed
     *  one is covered by [PinnedConsentStoreTest]. */
    private class InMemoryConsentStore : PinnedConsentStore {
        private var records: List<PinnedConsentRecord> = emptyList()
        override suspend fun load(): List<PinnedConsentRecord> = records
        override suspend fun save(records: List<PinnedConsentRecord>) {
            this.records = records
        }
    }
}
