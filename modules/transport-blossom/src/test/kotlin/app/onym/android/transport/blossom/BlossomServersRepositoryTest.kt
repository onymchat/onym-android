package app.onym.android.transport.blossom

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ─── make-active semantics (onboarding unblockers) ────────────
    //
    // Uploads/downloads target the FIRST configured server, so an
    // append-only add left a consented catalog pick silently inert
    // behind the seeded default. Mirrors iOS `addEndpoint(_:makeActive:)`
    // / `makeActive(url:)`.

    @Test
    fun addEndpoint_makeActive_insertsNewEndpointAtHead() = runTest {
        val repo = bootstrappedRepo()
        val picked = BlossomServerEndpoint.custom("https://picked.example")

        val added = repo.addEndpoint(picked, makeActive = true)

        assertTrue(added)
        assertEquals(
            listOf("https://picked.example", "https://blossom.onym.app"),
            repo.snapshots.value.endpoints.map { it.url },
        )
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun addEndpoint_makeActive_movesExistingEndpointToHead() = runTest {
        val repo = bootstrappedRepo()
        val picked = BlossomServerEndpoint.custom("https://picked.example")
        repo.addEndpoint(picked) // plain append: [official, picked]

        val added = repo.addEndpoint(picked, makeActive = true)

        assertFalse("existing URL is a move, not an insert", added)
        assertEquals(
            listOf("https://picked.example", "https://blossom.onym.app"),
            repo.snapshots.value.endpoints.map { it.url },
        )
    }

    @Test
    fun addEndpoint_makeActive_onExistingUrl_movesTheStoredRow_preservingIdentity() = runTest {
        // Consent-apply on a catalog seat whose URL equals the seeded
        // default: the STORED row (seed name + DEFAULT badge) moves to
        // the head; the passed endpoint's catalog identity must not
        // replace it.
        val repo = bootstrappedRepo()
        repo.addEndpoint(BlossomServerEndpoint.custom("https://picked.example"), makeActive = true)
        // Head is now picked; the seeded default sits behind it.

        val added = repo.addEndpoint(
            BlossomServerEndpoint(
                url = "https://blossom.onym.app",
                name = "Catalog Blob Module",
                isDefault = false,
            ),
            makeActive = true,
        )

        assertFalse(added)
        val head = repo.snapshots.value.endpoints.first()
        assertEquals("https://blossom.onym.app", head.url)
        assertEquals("Onym Official", head.name)
        assertTrue("seed identity (DEFAULT badge) must survive the move", head.isDefault)
    }

    @Test
    fun addEndpoint_makeActive_alreadyFirst_isNoOp_andDoesNotFlipInteraction() = runTest {
        // Aligned with makeActive(): "nothing moved" doesn't count as
        // user interaction, so the first-launch seed stays re-seedable.
        val repo = bootstrappedRepo()

        val added = repo.addEndpoint(
            BlossomServerEndpoint(
                url = "https://blossom.onym.app",
                name = "Catalog Blob Module",
                isDefault = false,
            ),
            makeActive = true,
        )

        assertFalse(added)
        val head = repo.snapshots.value.endpoints.first()
        assertEquals("Onym Official", head.name)
        assertTrue(head.isDefault)
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun addEndpoint_withoutMakeActive_stillAppends() = runTest {
        val repo = bootstrappedRepo()
        repo.addEndpoint(BlossomServerEndpoint.custom("https://appended.example"))
        assertEquals(
            listOf("https://blossom.onym.app", "https://appended.example"),
            repo.snapshots.value.endpoints.map { it.url },
        )
    }

    @Test
    fun makeActive_promotesConfiguredEndpoint() = runTest {
        val repo = bootstrappedRepo()
        repo.addEndpoint(BlossomServerEndpoint.custom("https://second.example"))

        repo.makeActive("https://second.example")

        assertEquals(
            listOf("https://second.example", "https://blossom.onym.app"),
            repo.snapshots.value.endpoints.map { it.url },
        )
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun makeActive_unknownUrl_isNoOp() = runTest {
        val repo = bootstrappedRepo()
        repo.makeActive("https://unknown.example")
        assertEquals(
            listOf("https://blossom.onym.app"),
            repo.snapshots.value.endpoints.map { it.url },
        )
        assertFalse("no-op must not count as user interaction", repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun makeActive_alreadyFirst_isNoOp_andDoesNotFlipInteraction() = runTest {
        val repo = bootstrappedRepo()
        repo.makeActive("https://blossom.onym.app")
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    // NOTE (extraction): the `viewModel_validatesScheme` test moved out of
    // this file — its subject is `app.onym.android.settings
    // .BlossomServerSettingsViewModel.validate`, which lives in :app, and a
    // library-module test cannot depend on :app. See
    // modules/transport-blossom/EXTRACTION-NOTES.md for the removed test
    // body; the integrator should re-home it next to the ViewModel.

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
}
