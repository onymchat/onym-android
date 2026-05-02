package chat.onym.android.chain

import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryRelayerSelectionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val a = RelayerEndpoint("A", "https://a.example", listOf("testnet"))
    private val b = RelayerEndpoint("B", "https://b.example", listOf("testnet"))
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

    // ─── PR #22: auto-populate on first launch ────────────────────

    @Test
    fun start_autoPopulates_whenColdConfigAndFetchedListNonEmpty() = runTest {
        // Cold-fresh install: no saved configuration. start() must
        // fan the published list into endpoints, sticking
        // hasUserInteracted=true so subsequent fetches don't churn.
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(a, b)))
        val (repo, store) = makeRepo(fetcher = fetcher)
        repo.bootstrap()  // loads RelayerConfiguration() from empty store

        repo.start()

        val cfg = repo.snapshots.value.configuration
        assertEquals(listOf(a, b), cfg.endpoints)
        assertNull(cfg.primaryUrl)
        assertEquals(RelayerStrategy.RANDOM, cfg.strategy)
        assertTrue("auto-populate must mark the configuration as user-touched", cfg.hasUserInteracted)
        // Persisted to disk too.
        assertEquals(listOf(a, b), store.loadConfiguration().endpoints)
    }

    @Test
    fun start_doesNotAutoPopulate_whenUserHasAlreadyInteracted() = runTest {
        // Pre-existing PR #20-era configuration with the user's
        // custom endpoint. start() loads the published list but
        // must NOT touch the configured endpoints.
        val store = InMemoryRelayerSelectionStore().apply {
            saveConfiguration(
                RelayerConfiguration(
                    endpoints = listOf(custom),
                    strategy = RelayerStrategy.PRIMARY,
                    primaryUrl = custom.url,
                    hasUserInteracted = true,
                )
            )
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(a, b)))
        val (repo, _) = makeRepo(store, fetcher)
        repo.bootstrap()

        repo.start()

        val cfg = repo.snapshots.value.configuration
        assertEquals(listOf(custom), cfg.endpoints)
        assertEquals(custom.url, cfg.primaryUrl)
        assertEquals(RelayerStrategy.PRIMARY, cfg.strategy)
    }

    @Test
    fun start_doesNotAutoPopulate_whenFetchedListIsEmpty() = runTest {
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()))
        val (repo, _) = makeRepo(fetcher = fetcher)
        repo.bootstrap()

        repo.start()

        val cfg = repo.snapshots.value.configuration
        assertTrue(cfg.endpoints.isEmpty())
        assertFalse(
            "no fetched list → no auto-populate → flag stays false (next refresh can still populate)",
            cfg.hasUserInteracted,
        )
    }

    @Test
    fun refresh_runsAutoPopulateWhenFlagStillFalse() = runTest {
        // Boot fetch failed (offline at launch); user opens the
        // picker and hits a refresh — the same auto-populate path
        // fires.
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Failing(IOException("offline")))
        val (repo, _) = makeRepo(fetcher = fetcher)
        repo.bootstrap()
        repo.start()  // failed silently; flag still false

        // Now point the fetcher at a successful response and refresh.
        fetcher.mode = FakeKnownRelayersFetcher.Mode.Succeeds(listOf(a, b))
        repo.refresh()

        val cfg = repo.snapshots.value.configuration
        assertEquals(listOf(a, b), cfg.endpoints)
        assertTrue(cfg.hasUserInteracted)
    }

    @Test
    fun mutators_promoteHasUserInteractedFromFalseToTrue() = runTest {
        // Cold config (hasUserInteracted = false). Each list-shaped
        // mutator must flip the flag — guards against a stale future
        // refresh fanning the published list back over the user's
        // explicit edits.
        val (repo, store) = makeRepo()
        repo.bootstrap()
        assertFalse(repo.snapshots.value.configuration.hasUserInteracted)

        repo.addEndpoint(custom)

        assertTrue(repo.snapshots.value.configuration.hasUserInteracted)
        assertTrue(store.loadConfiguration().hasUserInteracted)
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
        val refreshedA = a.copy(name = "Onym A (refreshed)", networks = listOf("public"))
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
        // PR #20 flipped the default strategy to RANDOM. This test
        // covers the PRIMARY-strategy resolution path, so promote
        // explicitly — without it the assertion races RANDOM's
        // uniform draw and fails ~50% of CI runs (flake caught on
        // the merged-main run for PR #20:
        // https://github.com/onymchat/onym-android/actions/runs/25258709184).
        repo.setStrategy(RelayerStrategy.PRIMARY)
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
        // Default strategy flipped to RANDOM in PR #22 — explicitly
        // set PRIMARY so this test still exercises the fall-back-to-
        // first path. (User-flow-wise: hits this when the user toggles
        // back to Primary after the default-random fan-in.)
        repo.setStrategy(RelayerStrategy.PRIMARY)
        // No setPrimary call → primaryUrl is null → fall through to first.
        assertEquals(a.url, repo.selectUrl())
    }

    // ─── PR #23: fetchStatus snapshots ────────────────────────────

    @Test
    fun bootstrap_leavesFetchStatusIdle() = runTest {
        val (repo, _) = makeRepo()
        repo.bootstrap()
        assertEquals(RelayerFetchStatus.Idle, repo.snapshots.value.fetchStatus)
    }

    @Test
    fun start_publishesSuccess_onHappyPath() = runTest {
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(listOf(a, b)))
        val (repo, _) = makeRepo(fetcher = fetcher)
        repo.bootstrap()

        repo.start()

        assertEquals(RelayerFetchStatus.Success, repo.snapshots.value.fetchStatus)
    }

    @Test
    fun start_publishesFailed_andStillSwallowsThrow_onError() = runTest {
        // Repository must wire the typed error into a localised
        // message via `errorMessageResolver` AND continue to swallow
        // the throw so app launch survives a network blip.
        val fetcher = FakeKnownRelayersFetcher(
            FakeKnownRelayersFetcher.Mode.Failing(RelayersFetchError.BadStatus(503))
        )
        val (repo, _) = makeRepo(
            fetcher = fetcher,
        ).let { (r, s) ->
            // Recreate with a controlled resolver so the assertion isn't
            // tied to the Throwable's default message.
            RelayerRepository(s, fetcher, errorMessageResolver = { "RESOLVED: ${it::class.simpleName}" }) to s
        }
        repo.bootstrap()

        repo.start()  // must not throw — `start` swallows after publishing Failed

        val status = repo.snapshots.value.fetchStatus
        assertTrue("status must be Failed, was $status", status is RelayerFetchStatus.Failed)
        assertEquals("RESOLVED: BadStatus", (status as RelayerFetchStatus.Failed).message)
    }

    @Test
    fun refresh_publishesFailed_andRethrows_onError() = runTest {
        // User-initiated retry: the Failed snapshot must be visible
        // to UI observers BEFORE the throw propagates.
        val store = InMemoryRelayerSelectionStore()
        val fetcher = FakeKnownRelayersFetcher(
            FakeKnownRelayersFetcher.Mode.Failing(RelayersFetchError.MalformedDocument(IOException("boom")))
        )
        val repo = RelayerRepository(
            store, fetcher,
            errorMessageResolver = { "RESOLVED: ${it::class.simpleName}" },
        )
        repo.bootstrap()

        val thrown = runCatching { repo.refresh() }.exceptionOrNull()

        assertTrue("refresh must rethrow", thrown is RelayersFetchError.MalformedDocument)
        val status = repo.snapshots.value.fetchStatus
        assertTrue(status is RelayerFetchStatus.Failed)
        assertEquals("RESOLVED: MalformedDocument", (status as RelayerFetchStatus.Failed).message)
    }

    @Test
    fun refresh_clearsStaleFailed_onSuccessfulRetry() = runTest {
        // A retry that succeeds must flip the snapshot back to
        // Success so the UI hides the error row.
        val fetcher = FakeKnownRelayersFetcher(
            FakeKnownRelayersFetcher.Mode.Failing(IOException("offline"))
        )
        val (repo, _) = makeRepo(fetcher = fetcher)
        repo.bootstrap()
        repo.start()
        assertTrue(repo.snapshots.value.fetchStatus is RelayerFetchStatus.Failed)

        fetcher.mode = FakeKnownRelayersFetcher.Mode.Succeeds(listOf(a))
        repo.refresh()

        assertEquals(RelayerFetchStatus.Success, repo.snapshots.value.fetchStatus)
    }
}
