package app.onym.android.discovery

import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * [DiscoveryRepository] semantics over the module's own testFixtures
 * fakes and the conformance fixtures: TOFU add flow, refresh with
 * chain-anchor retention, unpinned-source skipping, and durable
 * default removal.
 */
class DiscoveryRepositoryTest {

    private val manifestUrl = "https://discovery.onym.app/manifest.json"
    private val snapshotUrl = "https://discovery.onym.app/catalogs/public-all-seats.json"
    private val providerId = "onym:component:onym-discovery"

    private val now = Instant.parse("2026-08-14T00:00:00Z")

    private lateinit var fetcher: FakeDiscoveryFetcher
    private lateinit var store: InMemoryDiscoveryStore

    @Before
    fun setUp() {
        fetcher = FakeDiscoveryFetcher()
        store = InMemoryDiscoveryStore()
        fetcher.respond(manifestUrl, DiscoveryFixtures.load("provider-manifest.json"))
        fetcher.respond(snapshotUrl, DiscoveryFixtures.load("snapshot-1.json"))
    }

    private fun repository(
        defaults: List<DiscoverySource> = emptyList(),
    ) = DiscoveryRepository(
        store = store,
        fetcher = fetcher,
        defaultSources = defaults,
        now = { now },
    )

    private fun pinnedDefault() = DiscoverySource(
        url = manifestUrl,
        label = "Onym Discovery",
        providerId = providerId,
        pinnedOperatorKeyHex = DiscoveryFixtures.OPERATOR_KEY_HEX,
    )

    // ─── TOFU add flow ────────────────────────────────────────────

    @Test
    fun addSource_verifiesButDoesNotPersist_untilConfirmed() = runTest {
        val repo = repository()
        repo.bootstrap()

        val pending = repo.addSource(manifestUrl)
        assertEquals(DiscoveryFixtures.OPERATOR_KEY_HEX, pending.operatorKeyHex)
        // Nothing pinned yet — the fingerprint confirmation is pending.
        assertTrue(store.loadConfiguration().sources.isEmpty())
        assertTrue(repo.snapshots.value.sources.isEmpty())

        repo.confirmAddSource(pending, label = "Onym Discovery")
        val source = store.loadConfiguration().sources.single()
        assertEquals(DiscoveryFixtures.OPERATOR_KEY_HEX, source.pinnedOperatorKeyHex)
        assertEquals(providerId, source.providerId)
        assertTrue(store.loadConfiguration().hasUserInteracted)
    }

    @Test
    fun confirmAddSource_refreshes_publishingAttributedEntries() = runTest {
        val repo = repository()
        repo.bootstrap()
        repo.confirmAddSource(repo.addSource(manifestUrl), label = "Onym Discovery")

        val state = repo.snapshots.value
        assertEquals(DiscoveryFetchStatus.Success, state.fetchStatus)
        val attributed = state.entries.single()
        assertEquals("onym:component:onym-courier", attributed.entry.componentId)
        assertEquals(providerId, attributed.attribution.providerId)
        assertEquals("Onym Discovery", attributed.attribution.sourceLabel)
        assertEquals("public-all-seats", attributed.attribution.catalogId)
        assertEquals(EntryRelationship.CommonOwner, attributed.attribution.relationship)
        assertEquals("policy-ranked", attributed.attribution.placement)
        // The accepted snapshot's digest is retained for chain checks…
        val accepted = repo.snapshots.value.sources.single()
            .acceptedCatalogs.getValue("public-all-seats")
        assertEquals(1L, accepted.sequence)
        assertEquals(
            DiscoveryTrust.sha256Digest(DiscoveryFixtures.load("snapshot-1.json")),
            accepted.digest,
        )
        // …and matches the attribution's snapshot digest.
        assertEquals(accepted.digest, attributed.attribution.snapshotDigest)
    }

    // ─── refresh ──────────────────────────────────────────────────

    @Test
    fun refresh_advancesTheChainAnchor_acrossSequences() = runTest {
        val repo = repository(defaults = listOf(pinnedDefault()))
        repo.bootstrap()
        repo.refresh()
        assertEquals(1L, acceptedSequence(repo))

        fetcher.respond(snapshotUrl, DiscoveryFixtures.load("snapshot-2.json"))
        repo.refresh()
        assertEquals(2L, acceptedSequence(repo))

        fetcher.respond(snapshotUrl, DiscoveryFixtures.load("snapshot-3.json"))
        repo.refresh()
        assertEquals(3L, acceptedSequence(repo))
    }

    @Test
    fun refresh_rejectsRollback_keepingTheRetainedAnchor() = runTest {
        val repo = repository(defaults = listOf(pinnedDefault()))
        repo.bootstrap()
        fetcher.respond(snapshotUrl, DiscoveryFixtures.load("snapshot-2.json"))
        // First accept: sequence 2 (no prior anchor).
        repo.refresh()
        assertEquals(2L, acceptedSequence(repo))

        // Rollback: the host re-serves sequence 1.
        fetcher.respond(snapshotUrl, DiscoveryFixtures.load("snapshot-1.json"))
        try {
            repo.refresh()
            throw AssertionError("expected refresh to fail on rollback")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("sequence"))
        }
        // The anchor did not move; the failure is surfaced.
        assertEquals(2L, acceptedSequence(repo))
        assertTrue(repo.snapshots.value.fetchStatus is DiscoveryFetchStatus.Failed)
    }

    @Test
    fun refresh_skipsUnpinnedSources() = runTest {
        val unpinned = pinnedDefault().copy(pinnedOperatorKeyHex = null)
        store.saveConfiguration(
            DiscoverySourcesConfiguration(sources = listOf(unpinned))
        )
        val repo = repository()
        repo.bootstrap()
        repo.refresh()
        // Nothing was fetched — an unconfirmed source is never trusted.
        assertTrue(fetcher.fetchedUrls.isEmpty())
        assertTrue(repo.snapshots.value.entries.isEmpty())
    }

    @Test
    fun refresh_reacceptsUnchangedSnapshotBytes() = runTest {
        val repo = repository(defaults = listOf(pinnedDefault()))
        repo.bootstrap()
        repo.refresh()
        // Same bytes again (static host, nothing new published) — not
        // a rollback, the accepted anchor stays at sequence 1.
        repo.refresh()
        assertEquals(1L, acceptedSequence(repo))
        assertEquals(DiscoveryFetchStatus.Success, repo.snapshots.value.fetchStatus)
    }

    @Test
    fun start_isOnceOnly_onSuccess() = runTest {
        val repo = repository(defaults = listOf(pinnedDefault()))
        repo.bootstrap()
        repo.start()
        val callsAfterFirst = fetcher.fetchedUrls.size
        repo.start()
        assertEquals(callsAfterFirst, fetcher.fetchedUrls.size)
    }

    // ─── removal ──────────────────────────────────────────────────

    @Test
    fun removeSource_defaultProvider_isDurableAcrossBootstrap() = runTest {
        val repo = repository(defaults = listOf(pinnedDefault()))
        repo.bootstrap()
        repo.refresh()
        assertEquals(1, repo.snapshots.value.entries.size)

        repo.removeSource(providerId)
        assertTrue(repo.snapshots.value.sources.isEmpty())
        assertTrue(repo.snapshots.value.entries.isEmpty())
        assertTrue(providerId in store.loadConfiguration().removedDefaultProviderIds)

        // A fresh repository over the same store must NOT silently
        // restore the removed default (§8).
        val relaunched = repository(defaults = listOf(pinnedDefault()))
        relaunched.bootstrap()
        assertTrue(relaunched.snapshots.value.sources.isEmpty())
    }

    @Test
    fun confirmAddSource_reAddingARemovedDefault_clearsTheRemovalMarker() = runTest {
        val repo = repository(defaults = listOf(pinnedDefault()))
        repo.bootstrap()
        repo.removeSource(providerId)

        // Explicit user action — re-adding by URL goes back through TOFU.
        repo.confirmAddSource(repo.addSource(manifestUrl), label = "Onym Discovery")
        assertEquals(providerId, repo.snapshots.value.sources.single().providerId)
        assertNull(
            store.loadConfiguration().removedDefaultProviderIds.firstOrNull { it == providerId }
        )
    }
}

private fun acceptedSequence(repo: DiscoveryRepository): Long =
    repo.snapshots.value.sources.single()
        .acceptedCatalogs.getValue("public-all-seats").sequence
