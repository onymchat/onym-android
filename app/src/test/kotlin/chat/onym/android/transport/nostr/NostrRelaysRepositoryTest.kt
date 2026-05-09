package chat.onym.android.transport.nostr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals("wss://nostr.onym.chat", snap.endpoints[0].url)
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
        repo.removeEndpoint("wss://nostr.onym.chat")
        assertEquals(0, repo.snapshots.value.endpoints.size)
        assertTrue(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun resetToDefault_restoresSeedAndClearsInteraction() = runTest {
        val repo = bootstrappedRepo()
        repo.removeEndpoint("wss://nostr.onym.chat")
        assertTrue(repo.snapshots.value.hasUserInteracted)

        repo.resetToDefault()
        assertEquals(1, repo.snapshots.value.endpoints.size)
        assertFalse(repo.snapshots.value.hasUserInteracted)
    }

    @Test
    fun viewModel_validatesScheme() {
        assertNotNull(
            NostrRelaySettingsViewModelValidate("wss://x.com"),
        )
        assertNotNull(
            NostrRelaySettingsViewModelValidate("ws://localhost:7777"),
        )
        assertEquals(null, NostrRelaySettingsViewModelValidate("https://x.com"))
        assertEquals(null, NostrRelaySettingsViewModelValidate(""))
        assertEquals(null, NostrRelaySettingsViewModelValidate("   "))
    }

    private suspend fun bootstrappedRepo(): NostrRelaysRepository {
        val store = InMemoryNostrRelaysSelectionStore(NostrRelaysConfiguration.empty)
        val repo = NostrRelaysRepository(store)
        repo.bootstrap()
        return repo
    }

    /** Bridge to the static validator so this test file doesn't have
     *  to import the `chat.onym.android.settings` package. */
    private fun NostrRelaySettingsViewModelValidate(raw: String): String? =
        chat.onym.android.settings.NostrRelaySettingsViewModel.validate(raw)
}
