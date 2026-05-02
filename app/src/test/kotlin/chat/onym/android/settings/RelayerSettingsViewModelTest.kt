@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package chat.onym.android.settings

import chat.onym.android.chain.RelayerConfiguration
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.RelayerStrategy
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multi-endpoint settings ViewModel tests. Renamed from the PR #17
 * picker tests; covers add/remove/setPrimary/setStrategy intents
 * + URL validation + customDraft behaviour + unconfiguredKnownList
 * filtering.
 */
class RelayerSettingsViewModelTest {

    private val a = RelayerEndpoint("A", "https://a.example", "testnet")
    private val b = RelayerEndpoint("B", "https://b.example", "public")

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ─── intents → repository ─────────────────────────────────────

    @Test
    fun addKnown_dispatchesToRepository() = runTest {
        val (repo, vm) = makeViewModel()

        vm.addKnown(a)
        yield()

        assertEquals(listOf(a), repo.snapshots.value.configuration.endpoints)
    }

    @Test
    fun tappedAddCustom_validInput_persistsAsCustomEndpoint() = runTest {
        val (repo, vm) = makeViewModel()
        vm.customDraftChanged("https://localhost:9090 ")  // trailing whitespace
        yield()

        vm.tappedAddCustom()
        yield()

        val endpoints = repo.snapshots.value.configuration.endpoints
        assertEquals(1, endpoints.size)
        assertEquals("custom", endpoints.single().network)
        assertEquals("https://localhost:9090", endpoints.single().url)  // trimmed
    }

    @Test
    fun tappedAddCustom_clearsDraftOnSuccess() = runTest {
        val (_, vm) = makeViewModel()
        vm.customDraftChanged("https://x.example"); yield()

        vm.tappedAddCustom(); yield()

        assertEquals("", vm.state.value.customDraft)
        assertNull(vm.state.value.customDraftError)
    }

    @Test
    fun tappedAddCustom_invalidInput_setsErrorAndDoesNotPersist() = runTest {
        val (repo, vm) = makeViewModel()
        vm.customDraftChanged("ftp://no.example"); yield()

        vm.tappedAddCustom(); yield()

        assertNotNull("error must surface for invalid input", vm.state.value.customDraftError)
        assertTrue("repo must stay empty", repo.snapshots.value.configuration.endpoints.isEmpty())
    }

    @Test
    fun customDraftChanged_clearsStaleError() = runTest {
        val (_, vm) = makeViewModel()
        vm.customDraftChanged("ftp://no.example"); yield()
        vm.tappedAddCustom(); yield()
        assertNotNull(vm.state.value.customDraftError)

        vm.customDraftChanged("ftp://no.example/v2"); yield()

        assertNull(vm.state.value.customDraftError)
    }

    @Test
    fun removeEndpoint_dispatchesToRepository() = runTest {
        val (repo, vm) = makeViewModel(seedConfig = RelayerConfiguration(endpoints = listOf(a, b)))

        vm.removeEndpoint(a.url); yield()

        assertEquals(listOf(b), repo.snapshots.value.configuration.endpoints)
    }

    @Test
    fun setPrimary_dispatchesToRepository() = runTest {
        val (repo, vm) = makeViewModel(seedConfig = RelayerConfiguration(endpoints = listOf(a, b)))

        vm.setPrimary(b.url); yield()

        assertEquals(b.url, repo.snapshots.value.configuration.primaryUrl)
    }

    @Test
    fun setStrategy_dispatchesToRepository() = runTest {
        val (repo, vm) = makeViewModel(seedConfig = RelayerConfiguration(endpoints = listOf(a)))

        vm.setStrategy(RelayerStrategy.RANDOM); yield()

        assertEquals(RelayerStrategy.RANDOM, repo.snapshots.value.configuration.strategy)
    }

    // ─── unconfiguredKnownList filtering ──────────────────────────

    @Test
    fun unconfiguredKnownList_filtersAlreadyAddedUrls() = runTest {
        val (_, vm) = makeViewModel(
            seedKnown = listOf(a, b),
            seedConfig = RelayerConfiguration(endpoints = listOf(a)),
        )

        // `a.url` is already configured → only `b` should appear in
        // the "Add from Published List" section.
        assertEquals(listOf(b), vm.state.value.unconfiguredKnownList)
    }

    @Test
    fun unconfiguredKnownList_emptyWhenAllAdded() = runTest {
        val (_, vm) = makeViewModel(
            seedKnown = listOf(a, b),
            seedConfig = RelayerConfiguration(endpoints = listOf(a, b)),
        )
        assertTrue(vm.state.value.unconfiguredKnownList.isEmpty())
    }

    @Test
    fun unconfiguredKnownList_updatesAfterAdd() = runTest {
        val (_, vm) = makeViewModel(seedKnown = listOf(a, b))
        // Both available initially.
        assertEquals(listOf(a, b), vm.state.value.unconfiguredKnownList)

        vm.addKnown(a); yield()

        assertEquals(listOf(b), vm.state.value.unconfiguredKnownList)
    }

    // ─── URL validation (parity with PR #17) ──────────────────────

    @Test
    fun validate_rejectsEmpty() {
        assertFalse(RelayerSettingsViewModel.validate("") is RelayerSettingsViewModel.ValidationResult.Valid)
    }

    @Test
    fun validate_rejectsBlank() {
        assertFalse(RelayerSettingsViewModel.validate("   \t  ") is RelayerSettingsViewModel.ValidationResult.Valid)
    }

    @Test
    fun validate_rejectsGarbage() {
        assertFalse(RelayerSettingsViewModel.validate("not a url") is RelayerSettingsViewModel.ValidationResult.Valid)
    }

    @Test
    fun validate_rejectsFtpScheme() {
        assertFalse(RelayerSettingsViewModel.validate("ftp://r.example/") is RelayerSettingsViewModel.ValidationResult.Valid)
    }

    @Test
    fun validate_rejectsMissingScheme() {
        assertFalse(RelayerSettingsViewModel.validate("relayer.example/path") is RelayerSettingsViewModel.ValidationResult.Valid)
    }

    @Test
    fun validate_rejectsMissingHost() {
        assertFalse(RelayerSettingsViewModel.validate("https:///path") is RelayerSettingsViewModel.ValidationResult.Valid)
    }

    @Test
    fun validate_acceptsHttpsAndHttpAndLocalhost() {
        for (good in listOf("https://r.example", "http://localhost:8080", "https://r.example/p?q=1")) {
            assertTrue("expected $good to validate",
                RelayerSettingsViewModel.validate(good) is RelayerSettingsViewModel.ValidationResult.Valid)
        }
    }

    // ─── helpers ──────────────────────────────────────────────────

    private suspend fun makeViewModel(
        seedKnown: List<RelayerEndpoint> = emptyList(),
        seedConfig: RelayerConfiguration = RelayerConfiguration(),
    ): Pair<RelayerRepository, RelayerSettingsViewModel> {
        val store = InMemoryRelayerSelectionStore().apply {
            if (seedKnown.isNotEmpty()) saveCachedKnownRelayers(seedKnown)
            if (seedConfig.endpoints.isNotEmpty() || seedConfig.primaryUrl != null) {
                saveConfiguration(seedConfig)
            }
        }
        val fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(seedKnown))
        val repo = RelayerRepository(store, fetcher)
        repo.bootstrap()
        val vm = RelayerSettingsViewModel(repo)
        yield()
        return repo to vm
    }
}
