package chat.onym.android.chain

import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryRelayerSelectionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Repository contract for the multi-endpoint configuration shape.
 * Mirrors the iOS PR #20 RelayerRepositoryTests.
 */
class RelayerRepositoryTest {

    private val a = RelayerEndpoint("A", "https://a.example", "testnet")
    private val b = RelayerEndpoint("B", "https://b.example", "testnet")
    private val custom = RelayerEndpoint.custom("https://localhost:8080")

    private fun makeRepo(
        store: InMemoryRelayerSelectionStore = InMemoryRelayerSelectionStore(),
        fetcher: FakeKnownRelayersFetcher = FakeKnownRelayersFetcher(
            FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())
        ),
    ): Pair<RelayerRepository, InMemoryRelayerSelectionStore> {
        val repo = RelayerRepository(store, fetcher)
        return repo to store
    }

    // ─── bootstrap ────────────────────────────────────────────────

    @Test
    fun bootstrap_hydratesConfigurationAndCachedFromStore() = runTest {
        val store = InMemoryRelayerSelectionStore().apply {
            saveCachedKnownRelayers(listOf(a, b))
            saveConfiguration(RelayerConfiguration(endpoints = listOf(a), primaryUrl = a.url))
        }
        val (repo, _) = makeRepo(store)

        repo.bootstrap()

        val state = repo.snapshots.value
        assertEquals(listOf(a, b), state.knownRelayers)
        assertEquals(listOf(a), state.configuration.endpoints)
        assertEquals(a.url, state.configuration.primaryUrl)
    }

    // ─── start ────────────────────────────────────────────────────

    @Test
    fun start_isIdempotent_acrossMultipleCalls() = runTest {
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(a)))
        val (repo, _) = makeRepo(fetcher = fetcher)

        repo.start(); repo.start(); repo.start()

        assertEquals(1, fetcher.fetchCallCount)
    }

    @Test
    fun start_swallowsErrorsSilentlyAndKeepsCachedList() = runTest {
        val store = InMemoryRelayerSelectionStore().apply { saveCachedKnownRelayers(listOf(a)) }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Failing(IOException("offline")))
        val (repo, _) = makeRepo(store, fetcher)
        repo.bootstrap()

        repo.start()  // must not throw

        assertEquals(listOf(a), repo.snapshots.value.knownRelayers)
    }

    // ─── addEndpoint ──────────────────────────────────────────────

    @Test
    fun addEndpoint_appendsNewEndpoint_inInsertionOrder() = runTest {
        val (repo, store) = makeRepo()

        repo.addEndpoint(a)
        repo.addEndpoint(b)
        repo.addEndpoint(custom)

        assertEquals(listOf(a, b, custom), repo.snapshots.value.configuration.endpoints)
        assertEquals(listOf(a, b, custom), store.loadConfiguration().endpoints)
    }

    @Test
    fun addEndpoint_dedupesOnUrlAndUpdatesMetadataInPlace() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a)
        repo.addEndpoint(b)

        // Re-add `a`'s URL with new metadata (e.g. user typed it as
        // custom first, then the published list landed).
        val refreshedA = a.copy(name = "Onym A (refreshed)", network = "public")
        repo.addEndpoint(refreshedA)

        val endpoints = repo.snapshots.value.configuration.endpoints
        assertEquals(2, endpoints.size)  // no duplicate row
        assertEquals(refreshedA, endpoints[0])  // metadata updated in place
        assertEquals(b, endpoints[1])  // ordering preserved
    }

    @Test
    fun addEndpoint_doesNotAutoMarkAsPrimary() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a)
        repo.addEndpoint(b)

        // PRIMARY's fall-back path returns first-endpoint, but
        // primaryUrl itself stays null until the user picks.
        assertNull(repo.snapshots.value.configuration.primaryUrl)
    }

    // ─── removeEndpoint ───────────────────────────────────────────

    @Test
    fun removeEndpoint_dropsRow() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)

        repo.removeEndpoint(a.url)

        assertEquals(listOf(b), repo.snapshots.value.configuration.endpoints)
    }

    @Test
    fun removeEndpoint_clearsPrimaryMarker_whenRemovingPrimary() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)
        repo.setPrimary(a.url)

        repo.removeEndpoint(a.url)

        assertNull(repo.snapshots.value.configuration.primaryUrl)
        // selectUrl now falls back to first-of-remaining.
        assertEquals(b.url, repo.snapshots.value.selectUrl())
    }

    @Test
    fun removeEndpoint_doesNotClearPrimaryMarker_whenRemovingNonPrimary() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)
        repo.setPrimary(b.url)

        repo.removeEndpoint(a.url)

        assertEquals(b.url, repo.snapshots.value.configuration.primaryUrl)
    }

    @Test
    fun removeEndpoint_unknownUrl_isNoOp() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a)

        repo.removeEndpoint("https://no-such.example")

        assertEquals(listOf(a), repo.snapshots.value.configuration.endpoints)
    }

    // ─── setPrimary ───────────────────────────────────────────────

    @Test
    fun setPrimary_persistsAndPushes() = runTest {
        val (repo, store) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)

        repo.setPrimary(b.url)

        assertEquals(b.url, repo.snapshots.value.configuration.primaryUrl)
        assertEquals(b.url, store.loadConfiguration().primaryUrl)
    }

    @Test
    fun setPrimary_unknownUrl_isNoOp() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a)

        repo.setPrimary("https://not-configured.example")

        assertNull(repo.snapshots.value.configuration.primaryUrl)
    }

    // ─── setStrategy ──────────────────────────────────────────────

    @Test
    fun setStrategy_persistsAndPushes() = runTest {
        val (repo, store) = makeRepo()
        repo.addEndpoint(a)

        repo.setStrategy(RelayerStrategy.RANDOM)

        assertEquals(RelayerStrategy.RANDOM, repo.snapshots.value.configuration.strategy)
        assertEquals(RelayerStrategy.RANDOM, store.loadConfiguration().strategy)
    }

    // ─── selectUrl integration ────────────────────────────────────

    @Test
    fun selectUrl_respectsPrimaryStrategy() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)
        repo.setPrimary(b.url)

        assertEquals(b.url, repo.selectUrl())
    }

    @Test
    fun selectUrl_respectsRandomStrategy() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)
        repo.setStrategy(RelayerStrategy.RANDOM)

        val rng = Random(123)
        val visited = (0 until 200).map { repo.selectUrl(rng) }.toSet()
        assertTrue("RANDOM should visit both endpoints over many draws",
            visited == setOf(a.url, b.url))
    }

    @Test
    fun selectUrl_returnsNull_whenNoEndpoints() = runTest {
        val (repo, _) = makeRepo()
        assertNull(repo.selectUrl())
    }

    @Test
    fun selectUrl_primary_fallsBackToFirstWhenPrimaryStaleOrUnset() = runTest {
        val (repo, _) = makeRepo()
        repo.addEndpoint(a); repo.addEndpoint(b)
        // No setPrimary call → primaryUrl is null → fall through to first.
        assertEquals(a.url, repo.selectUrl())
    }
}
