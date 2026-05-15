@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State-machine tests for [ShareInviteViewModel] — the post-create
 * deeplink-share surface. Verifies:
 *
 *  - happy path: known group + active identity → [State.Ready] with
 *    a parseable [IntroCapability] link
 *  - missing group → [State.Failed] (e.g. host raced ahead before
 *    the persistence layer flushed)
 *  - missing identity → [State.Failed]
 *  - re-mint produces a fresh keypair (intro slots are
 *    independently revocable per share)
 */
class ShareInviteViewModelTest {

    // Share the scheduler with `runTest` so dispatches onto Main
    // (where `viewModelScope.launch` lands) get drained by the
    // test scope's `advanceUntilIdle`. Using a freshly-constructed
    // `UnconfinedTestDispatcher()` here would put Main on a
    // separate scheduler and the launched body would never run.
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(mainDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun mintFor_knownGroupAndIdentity_emitsReadyWithParseableLink() = runTest(mainDispatcher.scheduler) {
        val owner = IdentityId("alice")
        val group = makeGroup(id = "ab".repeat(32), owner = owner)
        val (vm, _) = harness(initialActive = owner, seed = listOf(group))

        vm.mintFor(group.id)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("expected Ready, got $state", state is ShareInviteViewModel.State.Ready)
        val ready = state as ShareInviteViewModel.State.Ready
        // Decode round-trips back to a capability for the same group.
        val cap = IntroCapability.fromLink(ready.link)
        assertNotNull(cap)
        assertEquals(group.name, ready.groupName)
        assertEquals(group.name, cap!!.groupName)
        assertEquals(group.id, cap.groupId.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun mintFor_unknownGroup_failsWithoutTouchingStore() = runTest(mainDispatcher.scheduler) {
        val owner = IdentityId("alice")
        val (vm, store) = harness(initialActive = owner, seed = emptyList())

        vm.mintFor("ab".repeat(32))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("expected Failed, got $state", state is ShareInviteViewModel.State.Failed)
        // No keypair was persisted for an unknown group.
        assertEquals(0, store.listForOwner(owner).size)
    }

    @Test
    fun mintFor_noActiveIdentity_failsBeforeMinting() = runTest(mainDispatcher.scheduler) {
        val owner = IdentityId("alice")
        val group = makeGroup(id = "ab".repeat(32), owner = owner)
        // Active identity is null — VM should bail before touching
        // the introducer.
        val (vm, store) = harness(initialActive = null, seed = listOf(group))

        vm.mintFor(group.id)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("expected Failed, got $state", state is ShareInviteViewModel.State.Failed)
        assertEquals(0, store.listForOwner(owner).size)
    }

    @Test
    fun mintFor_calledTwice_mintsTwoIndependentKeypairs() = runTest(mainDispatcher.scheduler) {
        val owner = IdentityId("alice")
        val group = makeGroup(id = "ab".repeat(32), owner = owner)
        val (vm, store) = harness(initialActive = owner, seed = listOf(group))

        vm.mintFor(group.id)
        advanceUntilIdle()
        val first = (vm.state.value as ShareInviteViewModel.State.Ready).link
        vm.mintFor(group.id)
        advanceUntilIdle()
        val second = (vm.state.value as ShareInviteViewModel.State.Ready).link

        // Per-link revocation depends on this — re-shares cannot
        // collapse to the same intro slot or revoking one would kill
        // the other.
        assertTrue("two shares should produce different links", first != second)
        assertEquals(2, store.listForOwner(owner).size)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun TestScope.harness(
        initialActive: IdentityId?,
        seed: List<ChatGroup>,
    ): Pair<ShareInviteViewModel, InMemoryIntroKeyStore> {
        val store = InMemoryGroupStore()
        // preload is suspend; runBlocking inside a test scope is
        // fine — no foreign coroutines to coordinate with.
        kotlinx.coroutines.runBlocking { store.preload(seed) }
        val active = FakeActiveIdentityProvider(initial = initialActive)
        val groupRepo = GroupRepository(
            store = store,
            identity = active,
            scope = this,
        )
        kotlinx.coroutines.runBlocking { groupRepo.reload() }
        val introKeyStore = InMemoryIntroKeyStore()
        val vm = ShareInviteViewModel(
            identity = active,
            // Pin the introducer's IO dispatcher to the test
            // scheduler. Otherwise `withContext(Dispatchers.IO)`
            // dispatches onto the real I/O pool and the resume back
            // to Main races test teardown — visible as
            // CoroutinesInternalError reported on whichever test
            // happens to be running when the leaked continuation
            // resumes.
            introducer = InviteIntroducer(
                store = introKeyStore,
                ioDispatcher = mainDispatcher,
            ),
            groupRepository = groupRepo,
        )
        return vm to introKeyStore
    }

    private fun makeGroup(id: String, owner: IdentityId): ChatGroup = ChatGroup(
        id = id,
        name = "Test group",
        groupSecret = ByteArray(32) { 0x11 },
        createdAtMillis = 1_700_000_000_000L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32) { 0x22 },
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
        ownerIdentityId = owner.value,
    )
}
