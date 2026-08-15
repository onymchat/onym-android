package app.onym.android.discovery

import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The shipped [DiscoverySource.onymDefault] the app seeds (PR 3
 * onboarding wiring). The invariant that must never regress: it ships
 * UNPINNED — pinning is the user's own TOFU act — so seeding it never
 * causes network trust by itself (refresh skips unpinned sources).
 */
class DiscoveryOnymDefaultTest {

    @Test
    fun onymDefault_isUnpinned_byDesign() {
        assertNull(
            "the bundled default must go through the user's own TOFU confirmation",
            DiscoverySource.onymDefault.pinnedOperatorKeyHex,
        )
        assertNull(DiscoverySource.onymDefault.operatorKeyFingerprint)
    }

    @Test
    fun onymDefault_shape() {
        assertEquals("https://discovery.onym.app/manifest.json", DiscoverySource.onymDefault.url)
        assertEquals("onym:component:onym-discovery", DiscoverySource.onymDefault.providerId)
        assertEquals("Onym Discovery", DiscoverySource.onymDefault.label)
    }

    @Test
    fun bootstrap_seedsTheDefault_andRefreshTrustsNothingFromIt() = runTest {
        val fetcher = FakeDiscoveryFetcher()
        val store = InMemoryDiscoveryStore()
        val repo = DiscoveryRepository(
            store = store,
            fetcher = fetcher,
            defaultSources = listOf(DiscoverySource.onymDefault),
            now = { Instant.parse("2026-08-14T00:00:00Z") },
        )

        repo.bootstrap()

        val seeded = repo.snapshots.value.sources.single()
        assertEquals(DiscoverySource.onymDefault.providerId, seeded.providerId)
        assertNull(seeded.pinnedOperatorKeyHex)

        // Refresh skips the unpinned source entirely — nothing is
        // fetched, nothing is trusted.
        repo.refresh()
        assertTrue(fetcher.fetchedUrls.isEmpty())
        assertTrue(repo.snapshots.value.entries.isEmpty())
    }
}
