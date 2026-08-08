package app.onym.android.transport.nostr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for [NostrRelaysRepository] — first-launch seed,
 * sticky-interaction bit, add / remove / reset semantics.
 *
 * Mirrors `NostrRelaysRepositoryTests.swift` from onym-ios PR #87.
 */
class NostrRelaysRepositoryTest {

    @Test
    fun firstLaunch_seedsOnymOfficial() = runTest {
        val store = InMemoryNostrRelaysSelectionStore(NostrRelaysConfiguration.empty)
        val repo = NostrRelaysRepository(store)
        repo.bootstrap()

        val snap = repo.snapshots.value
        assertEquals(1, snap.endpoints.size)
        assertEquals("wss://nostr.onym.app", snap.endpoints[0].url)
        assertTrue(snap.endpoints[0].isDefault)
        assertFalse("seed leaves hasUserInteracted=false", snap.hasUserInteracted)
        // Seed is persisted so subsequent boots load it directly.
        assertEquals(snap, store.load())
    }

    @Test
    fun userClearedAll_doesNotReSeedOnRelaunch() = runTest {
        // User cleared the list — store has empty endpoints +
        // hasUserInteracted = true.
        val cleared = NostrRelaysConfiguration(emptyList(), hasUserInteracted = true)
        val store = InMemoryNostrRelaysSelectionStore(cleared)
        val repo = NostrRelaysRepository(store)
        repo.bootstrap()
        assertEquals(0, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun addEndpoint_appendsAndFlipsInteraction() = runTest {
        val repo = bootstrappedRepo()
        val added = repo.addEndpoint(NostrRelayEndpoint.custom("wss://relay.example.com"))
        assertTrue(added)
        assertEquals(2, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun addEndpoint_duplicateIsNoOp() = runTest {
        val repo = bootstrappedRepo()
        val added = repo.addEndpoint(NostrRelayEndpoint.onymOfficial)
        assertFalse(added)
        assertEquals(1, repo.snapshots.value.endpoints.size)
    }

    @Test
    fun removeEndpoint_drops() = runTest {
        val repo = bootstrappedRepo()
        repo.removeEndpoint("wss://nostr.onym.app")
        assertEquals(0, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun resetToDefault_restoresSeedAndClearsInteraction() = runTest {
        val repo = bootstrappedRepo()
        repo.removeEndpoint("wss://nostr.onym.app")
        assertTrue(repo.snapshots.value.hasUserInteracted)

        repo.resetToDefault()
        assertEquals(1, repo.snapshots.value.endpoints.size)
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    // `viewModel_validatesScheme` re-homed to :app's
    // NostrRelaySettingsViewModelTest — its subject
    // (app.onym.android.settings.NostrRelaySettingsViewModel.validate)
    // lives in :app, which a library test cannot depend on.

    // ─── GitHub-published default fetch ───────────────────────────

    private class StubFetcher(
        private val result: Result<List<NostrRelayEndpoint>>,
    ) : KnownNostrRelaysFetcher {
        override suspend fun fetch(): List<NostrRelayEndpoint> = result.getOrThrow()
    }

    private val published = NostrRelayEndpoint("wss://published.example", "Published", isDefault = true)

    @Test
    fun refresh_installsPublishedList_whenNotUserInteracted() = runTest {
        val store = InMemoryNostrRelaysSelectionStore(NostrRelaysConfiguration.empty)
        val repo = NostrRelaysRepository(store, StubFetcher(Result.success(listOf(published))))
        repo.bootstrap() // seeds hardcoded default
        repo.refresh()   // replaces it with the published list
        assertEquals(listOf(published), repo.snapshots.value.endpoints)
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun refresh_doesNotOverwriteUserCustomisedList() = runTest {
        val custom = NostrRelayEndpoint.custom("wss://mine.example")
        val store = InMemoryNostrRelaysSelectionStore(
            NostrRelaysConfiguration(listOf(custom), hasUserInteracted = true),
        )
        val repo = NostrRelaysRepository(store, StubFetcher(Result.success(listOf(published))))
        repo.bootstrap()
        repo.refresh()
        assertEquals(listOf(custom), repo.snapshots.value.endpoints)
    }

    @Test
    fun resetToDefault_fetchesPublishedList() = runTest {
        val store = InMemoryNostrRelaysSelectionStore(NostrRelaysConfiguration.empty)
        val repo = NostrRelaysRepository(store, StubFetcher(Result.success(listOf(published))))
        repo.bootstrap()
        repo.resetToDefault()
        assertEquals(listOf(published), repo.snapshots.value.endpoints)
    }

    @Test
    fun resetToDefault_offline_fallsBackToSeed() = runTest {
        val store = InMemoryNostrRelaysSelectionStore(NostrRelaysConfiguration.empty)
        val repo = NostrRelaysRepository(store, StubFetcher(Result.failure(java.io.IOException("offline"))))
        repo.bootstrap()
        repo.resetToDefault()
        assertEquals("wss://nostr.onym.app", repo.snapshots.value.endpoints.single().url)
    }

    private suspend fun bootstrappedRepo(): NostrRelaysRepository {
        val store = InMemoryNostrRelaysSelectionStore(NostrRelaysConfiguration.empty)
        val repo = NostrRelaysRepository(store)
        repo.bootstrap()
        return repo
    }
}
