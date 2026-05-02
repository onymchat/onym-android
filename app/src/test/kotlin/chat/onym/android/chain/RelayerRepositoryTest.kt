package chat.onym.android.chain

import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryRelayerSelectionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Repository contract against the in-memory fakes. Same shape as
 * [chat.onym.android.identity.IdentityRepository]'s test pattern —
 * Mutex + StateFlow emission discipline.
 *
 * Mirrors `RelayerRepositoryTests` from onym-ios PR #18.
 */
class RelayerRepositoryTest {

    private val testnet = RelayerEndpoint("Onym Testnet", "https://relayer-testnet.onym.chat", "testnet")
    private val mainnet = RelayerEndpoint("Onym Mainnet", "https://relayer.onym.chat", "public")

    // ─── bootstrap ────────────────────────────────────────────────

    @Test
    fun bootstrap_hydratesCachedAndSelectionFromStore() = runTest {
        val store = InMemoryRelayerSelectionStore().apply {
            saveCachedKnownRelayers(listOf(testnet, mainnet))
            saveSelection(RelayerSelection.Known(testnet))
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Failing(IOException("offline")))
        val repo = RelayerRepository(store, fetcher)

        repo.bootstrap()

        val state = repo.snapshots.value
        assertEquals(listOf(testnet, mainnet), state.knownRelayers)
        assertEquals(RelayerSelection.Known(testnet), state.selection)
        assertEquals(0, fetcher.fetchCallCount)
    }

    // ─── start ────────────────────────────────────────────────────

    @Test
    fun start_persistsAndPushesFreshList() = runTest {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(testnet, mainnet)))
        val repo = RelayerRepository(store, fetcher)

        repo.start()

        assertEquals(listOf(testnet, mainnet), store.loadCachedKnownRelayers())
        assertEquals(listOf(testnet, mainnet), repo.snapshots.value.knownRelayers)
    }

    @Test
    fun start_isIdempotent_acrossMultipleCalls() = runTest {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(testnet)))
        val repo = RelayerRepository(store, fetcher)

        repo.start()
        repo.start()
        repo.start()

        assertEquals(
            "start() must only fetch once across multiple calls",
            1,
            fetcher.fetchCallCount,
        )
    }

    @Test
    fun start_swallowsNetworkErrorsSilently() = runTest {
        val store = InMemoryRelayerSelectionStore().apply {
            saveCachedKnownRelayers(listOf(testnet))
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Failing(IOException("offline")))
        val repo = RelayerRepository(store, fetcher)
        repo.bootstrap()

        // Must NOT throw — app launch can't crash on a network blip.
        repo.start()

        // Cached list survives the failed fetch.
        assertEquals(listOf(testnet), repo.snapshots.value.knownRelayers)
        assertEquals(listOf(testnet), store.loadCachedKnownRelayers())
    }

    @Test
    fun start_failureLeavesCachedListIntact() = runTest {
        val store = InMemoryRelayerSelectionStore().apply {
            saveCachedKnownRelayers(listOf(mainnet))
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Failing(IOException("dns")))
        val repo = RelayerRepository(store, fetcher)
        repo.bootstrap()

        repo.start()

        // Selection (none) and known list (cached mainnet) both
        // unchanged after the failed fetch.
        assertEquals(listOf(mainnet), repo.snapshots.value.knownRelayers)
        assertNull(repo.snapshots.value.selection)
    }

    // ─── refresh ──────────────────────────────────────────────────

    @Test
    fun refresh_succeeds_persistsAndPushes() = runTest {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(testnet)))
        val repo = RelayerRepository(store, fetcher)

        repo.refresh()

        assertEquals(listOf(testnet), repo.snapshots.value.knownRelayers)
        assertEquals(listOf(testnet), store.loadCachedKnownRelayers())
    }

    @Test
    fun refresh_propagatesFailureToCaller() = runTest {
        val store = InMemoryRelayerSelectionStore().apply {
            saveCachedKnownRelayers(listOf(testnet))
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Failing(IOException("dns")))
        val repo = RelayerRepository(store, fetcher)
        repo.bootstrap()

        var thrown: Throwable? = null
        try { repo.refresh() } catch (t: Throwable) { thrown = t }

        assertTrue("refresh must propagate so pull-to-refresh UI can react", thrown is IOException)
        // Cached list still intact.
        assertEquals(listOf(testnet), repo.snapshots.value.knownRelayers)
    }

    // ─── setSelection ─────────────────────────────────────────────

    @Test
    fun setSelection_persistsAndPushes() = runTest {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()))
        val repo = RelayerRepository(store, fetcher)

        repo.setSelection(RelayerSelection.Custom("https://custom.example"))

        assertEquals(
            RelayerSelection.Custom("https://custom.example"),
            repo.snapshots.value.selection,
        )
        assertEquals(
            RelayerSelection.Custom("https://custom.example"),
            store.loadSelection(),
        )
    }

    @Test
    fun setSelection_nullClears() = runTest {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()))
        val repo = RelayerRepository(store, fetcher)
        repo.setSelection(RelayerSelection.Custom("https://x.example"))

        repo.setSelection(null)

        assertNull(repo.snapshots.value.selection)
        assertNull(store.loadSelection())
    }

    // ─── StateFlow discipline ─────────────────────────────────────

    @Test
    fun snapshots_initialValue_isEmpty() {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()))
        val repo = RelayerRepository(store, fetcher)

        val s = repo.snapshots.value
        assertTrue(s.knownRelayers.isEmpty())
        assertNull(s.selection)
    }

    @Test
    fun snapshots_emitsAfterEachSuccessfulMutation() = runTest {
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(testnet)))
        val repo = RelayerRepository(store, fetcher)

        val initial = repo.snapshots.value
        repo.start()
        val afterStart = repo.snapshots.value
        repo.setSelection(RelayerSelection.Known(testnet))
        val afterSelection = repo.snapshots.value

        // Three distinct snapshots.
        assertTrue("start() should change the knownRelayers list",
            initial.knownRelayers != afterStart.knownRelayers)
        assertTrue("setSelection() should change selection",
            afterStart.selection != afterSelection.selection)
        // Identity sanity.
        assertSame(repo.snapshots, repo.snapshots)
    }
}
