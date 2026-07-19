package app.onym.android.transport.blossom

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for [BlossomServersRepository] — first-launch seed,
 * sticky-interaction bit, add / remove / reset semantics.
 *
 * Mirrors [app.onym.android.transport.nostr.NostrRelaysRepository]'s
 * tests and onym-ios `BlossomServersRepositoryTests.swift`.
 */
class BlossomServersRepositoryTest {

    @Test
    fun firstLaunch_seedsOnymOfficial() = runTest {
        val store = InMemoryBlossomServersSelectionStore(BlossomServersConfiguration.empty)
        val repo = BlossomServersRepository(store)
        repo.bootstrap()

        val snap = repo.snapshots.value
        assertEquals(1, snap.endpoints.size)
        assertEquals("https://blossom.onym.app", snap.endpoints[0].url)
        assertTrue(snap.endpoints[0].isDefault)
        assertFalse("seed leaves hasUserInteracted=false", snap.hasUserInteracted)
        assertEquals(snap, store.load())
    }

    @Test
    fun userClearedAll_doesNotReSeedOnRelaunch() = runTest {
        val cleared = BlossomServersConfiguration(emptyList(), hasUserInteracted = true)
        val store = InMemoryBlossomServersSelectionStore(cleared)
        val repo = BlossomServersRepository(store)
        repo.bootstrap()
        assertEquals(0, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun addEndpoint_appendsAndFlipsInteraction() = runTest {
        val repo = bootstrappedRepo()
        val added = repo.addEndpoint(BlossomServerEndpoint.custom("https://blossom.example.com"))
        assertTrue(added)
        assertEquals(2, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun addEndpoint_duplicateIsNoOp() = runTest {
        val repo = bootstrappedRepo()
        val added = repo.addEndpoint(BlossomServerEndpoint.onymOfficial)
        assertFalse(added)
        assertEquals(1, repo.snapshots.value.endpoints.size)
    }

    @Test
    fun removeEndpoint_drops() = runTest {
        val repo = bootstrappedRepo()
        repo.removeEndpoint("https://blossom.onym.app")
        assertEquals(0, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun resetToDefault_restoresSeedAndClearsInteraction() = runTest {
        val repo = bootstrappedRepo()
        repo.removeEndpoint("https://blossom.onym.app")
        assertTrue(repo.snapshots.value.hasUserInteracted)

        repo.resetToDefault()
        assertEquals(1, repo.snapshots.value.endpoints.size)
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun viewModel_validatesScheme() {
        assertNotNull(validate("https://x.com"))
        assertNotNull(validate("http://localhost:3000"))
        assertEquals(null, validate("wss://x.com"))
        assertEquals(null, validate(""))
        assertEquals(null, validate("   "))
    }

    // ─── GitHub-published default fetch ───────────────────────────

    private class StubFetcher(
        private val result: Result<List<BlossomServerEndpoint>>,
    ) : KnownBlossomServersFetcher {
        override suspend fun fetch(): List<BlossomServerEndpoint> = result.getOrThrow()
    }

    private val published = BlossomServerEndpoint("https://published.example", "Published", isDefault = true)

    @Test
    fun refresh_installsPublishedList_whenNotUserInteracted() = runTest {
        val store = InMemoryBlossomServersSelectionStore(BlossomServersConfiguration.empty)
        val repo = BlossomServersRepository(store, StubFetcher(Result.success(listOf(published))))
        repo.bootstrap()
        repo.refresh()
        assertEquals(listOf(published), repo.snapshots.value.endpoints)
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun refresh_doesNotOverwriteUserCustomisedList() = runTest {
        val custom = BlossomServerEndpoint.custom("https://mine.example")
        val store = InMemoryBlossomServersSelectionStore(
            BlossomServersConfiguration(listOf(custom), hasUserInteracted = true),
        )
        val repo = BlossomServersRepository(store, StubFetcher(Result.success(listOf(published))))
        repo.bootstrap()
        repo.refresh()
        assertEquals(listOf(custom), repo.snapshots.value.endpoints)
    }

    @Test
    fun resetToDefault_fetchesPublishedList() = runTest {
        val store = InMemoryBlossomServersSelectionStore(BlossomServersConfiguration.empty)
        val repo = BlossomServersRepository(store, StubFetcher(Result.success(listOf(published))))
        repo.bootstrap()
        repo.resetToDefault()
        assertEquals(listOf(published), repo.snapshots.value.endpoints)
    }

    @Test
    fun resetToDefault_offline_fallsBackToSeed() = runTest {
        val store = InMemoryBlossomServersSelectionStore(BlossomServersConfiguration.empty)
        val repo = BlossomServersRepository(store, StubFetcher(Result.failure(java.io.IOException("offline"))))
        repo.bootstrap()
        repo.resetToDefault()
        assertEquals("https://blossom.onym.app", repo.snapshots.value.endpoints.single().url)
    }

    private suspend fun bootstrappedRepo(): BlossomServersRepository {
        val store = InMemoryBlossomServersSelectionStore(BlossomServersConfiguration.empty)
        val repo = BlossomServersRepository(store)
        repo.bootstrap()
        return repo
    }

    /** Bridge to the static validator so this test file doesn't have to
     *  import the `app.onym.android.settings` package. */
    private fun validate(raw: String): String? =
        app.onym.android.settings.BlossomServerSettingsViewModel.validate(raw)
}
