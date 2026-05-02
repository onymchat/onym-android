@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package chat.onym.android.settings

import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.RelayerSelection
import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryRelayerSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Picker ViewModel tests — intents, repository wiring, URL
 * validation, draft prefill. The ViewModel uses
 * [androidx.lifecycle.viewModelScope] which dispatches on
 * `Dispatchers.Main` by default; tests override Main to
 * [UnconfinedTestDispatcher] for deterministic ordering.
 *
 * Mirrors `RelayerPickerFlowTests` from onym-ios PR #18.
 */
class RelayerPickerViewModelTest {

    private val testnet = RelayerEndpoint("Onym Testnet", "https://relayer-testnet.onym.chat", "testnet")
    private val mainnet = RelayerEndpoint("Onym Mainnet", "https://relayer.onym.chat", "public")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── intents → repository ─────────────────────────────────────

    @Test
    fun pickKnown_dispatchesToRepository() = runTest {
        val (repo, vm) = makeViewModel(seedKnown = listOf(testnet, mainnet))

        vm.pickKnown(testnet)
        yield()

        assertEquals(RelayerSelection.Known(testnet), repo.snapshots.value.selection)
    }

    @Test
    fun saveCustom_validInput_dispatchesToRepository() = runTest {
        val (repo, vm) = makeViewModel()
        vm.customDraftChanged("https://custom.example/relayer ")  // trailing whitespace
        yield()

        vm.saveCustom()
        yield()

        // Whitespace trimmed before persisting.
        assertEquals(
            RelayerSelection.Custom("https://custom.example/relayer"),
            repo.snapshots.value.selection,
        )
    }

    @Test
    fun clearSelection_dispatchesNullToRepository() = runTest {
        val (repo, vm) = makeViewModel()
        vm.customDraftChanged("https://x.example")
        yield()
        vm.saveCustom(); yield()

        vm.clearSelection()
        yield()

        assertNull(repo.snapshots.value.selection)
    }

    // ─── draft management ─────────────────────────────────────────

    @Test
    fun customDraft_changes_areLocalUntilSave() = runTest {
        val (repo, vm) = makeViewModel()

        vm.customDraftChanged("https://typing.example")
        yield()

        // Draft visible on the ViewModel; repo untouched.
        assertEquals("https://typing.example", vm.state.value.customDraft)
        assertNull(repo.snapshots.value.selection)
    }

    @Test
    fun customDraft_changing_clearsStaleError() = runTest {
        val (_, vm) = makeViewModel()
        vm.customDraftChanged("ftp://no.example"); yield()
        vm.saveCustom(); yield()
        assertNotNull("error must surface after invalid save", vm.state.value.customDraftError)

        vm.customDraftChanged("ftp://no.example/v2"); yield()

        assertNull("typing should clear the prior error", vm.state.value.customDraftError)
    }

    @Test
    fun draft_prefillFromExistingCustomSelection() = runTest {
        val store = InMemoryRelayerSelectionStore().apply {
            saveSelection(RelayerSelection.Custom("https://existing.example"))
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()))
        val repo = RelayerRepository(store, fetcher)
        repo.bootstrap()

        val vm = RelayerPickerViewModel(repo)
        yield()

        assertEquals("https://existing.example", vm.state.value.customDraft)
    }

    @Test
    fun draft_isEmptyWhenSelectionIsKnown() = runTest {
        val (_, vm) = makeViewModel(seedKnown = listOf(testnet))
        // No prior selection set; draft starts empty.

        assertEquals("", vm.state.value.customDraft)
    }

    // ─── snapshot mirror ──────────────────────────────────────────

    @Test
    fun knownRelayers_areMirroredFromRepository() = runTest {
        val (_, vm) = makeViewModel(seedKnown = listOf(testnet, mainnet))

        assertEquals(listOf(testnet, mainnet), vm.state.value.knownRelayers)
    }

    // ─── URL validation ───────────────────────────────────────────

    @Test
    fun validate_rejectsEmpty() {
        val r = RelayerPickerViewModel.validate("")
        assertTrue(r is RelayerPickerViewModel.ValidationResult.Invalid)
    }

    @Test
    fun validate_rejectsBlank() {
        val r = RelayerPickerViewModel.validate("   \t\n  ")
        assertTrue(r is RelayerPickerViewModel.ValidationResult.Invalid)
    }

    @Test
    fun validate_rejectsGarbage() {
        val r = RelayerPickerViewModel.validate("not a url at all")
        assertTrue(r is RelayerPickerViewModel.ValidationResult.Invalid)
    }

    @Test
    fun validate_rejectsFtpScheme() {
        val r = RelayerPickerViewModel.validate("ftp://relayer.example/")
        assertTrue(r is RelayerPickerViewModel.ValidationResult.Invalid)
    }

    @Test
    fun validate_rejectsMissingScheme() {
        val r = RelayerPickerViewModel.validate("relayer.example/path")
        assertTrue(r is RelayerPickerViewModel.ValidationResult.Invalid)
    }

    @Test
    fun validate_rejectsMissingHost() {
        val r = RelayerPickerViewModel.validate("https:///path")
        assertTrue(r is RelayerPickerViewModel.ValidationResult.Invalid)
    }

    @Test
    fun validate_acceptsHttpsAndHttpAndLocalhost() {
        for (good in listOf(
            "https://relayer.example",
            "http://localhost:8080",
            "https://relayer.example/path?x=1",
        )) {
            val r = RelayerPickerViewModel.validate(good)
            assertTrue("expected $good to validate, got $r",
                r is RelayerPickerViewModel.ValidationResult.Valid)
        }
    }

    // ─── helpers ──────────────────────────────────────────────────

    private suspend fun makeViewModel(
        seedKnown: List<RelayerEndpoint> = emptyList(),
        seedSelection: RelayerSelection? = null,
    ): Pair<RelayerRepository, RelayerPickerViewModel> {
        val store = InMemoryRelayerSelectionStore().apply {
            if (seedKnown.isNotEmpty()) saveCachedKnownRelayers(seedKnown)
            if (seedSelection != null) saveSelection(seedSelection)
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(seedKnown))
        val repo = RelayerRepository(store, fetcher)
        repo.bootstrap()
        val vm = RelayerPickerViewModel(repo)
        yield()  // let the init { } collector hydrate
        return repo to vm
    }
}
