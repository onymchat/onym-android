@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State-machine tests for [JoinViewModel]. Covers:
 *
 *  - Ready → Sending → AwaitingApproval (transport accepts).
 *  - Ready → Sending → Failed (no identity / transport rejects).
 *  - Already-a-member: a matching group present at construction
 *    flips straight to Approved without sending.
 *  - Mid-flight materialization: AwaitingApproval auto-flips to
 *    Approved when the group appears in the repository.
 *  - Debounce: a second send() while the first is in flight is a
 *    no-op (no duplicate submitRequest call).
 *
 * The full crypto round-trip (joiner → seal → inviter approves →
 * sealed invitation arrives → group materializes) is covered in
 * the integration test landing with the inviter-side approval UI
 * in a follow-up PR. JoinViewModel is exercised here against a
 * stub submitRequest lambda so the state machine is visible
 * without standing up the full inbox-fanout pipeline.
 */
class JoinViewModelTest {

    // Share the scheduler with `runTest` so dispatches onto Main
    // (where viewModelScope.launch lands) get drained by
    // `advanceUntilIdle`. Same trick used by ShareInviteViewModelTest.
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(mainDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val groupId = ByteArray(32) { (it + 1).toByte() }
    private val introPub = ByteArray(32) { (it * 3 % 251).toByte() }
    private val capability: IntroCapability
        get() = IntroCapability(introPub, groupId, "Test group")

    @Test
    fun send_acceptedTransport_movesToAwaitingApproval() = runTest(mainDispatcher.scheduler) {
        val (vm, _, _) = harness(outcome = JoinRequestSender.Outcome.Sent)

        vm.send("alice")
        advanceUntilIdle()

        assertTrue(
            "expected AwaitingApproval, got ${vm.state.value}",
            vm.state.value is JoinViewModel.State.AwaitingApproval,
        )
    }

    @Test
    fun send_noIdentity_movesToFailed() = runTest(mainDispatcher.scheduler) {
        val (vm, _, _) = harness(outcome = JoinRequestSender.Outcome.NoIdentityLoaded())

        vm.send("alice")
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("expected Failed, got $s", s is JoinViewModel.State.Failed)
        assertEquals("Sign in first.", (s as JoinViewModel.State.Failed).reason)
    }

    @Test
    fun send_transportFailed_surfacesReason() = runTest(mainDispatcher.scheduler) {
        val (vm, _, _) = harness(
            outcome = JoinRequestSender.Outcome.TransportFailed("relay timeout"),
        )

        vm.send("alice")
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("expected Failed, got $s", s is JoinViewModel.State.Failed)
        assertTrue(
            "reason should mention the transport reason: ${(s as JoinViewModel.State.Failed).reason}",
            s.reason.contains("relay timeout"),
        )
    }

    @Test
    fun groupAlreadyInRepository_flipsToApprovedAtConstruction() = runTest(mainDispatcher.scheduler) {
        val owner = IdentityId("alice")
        val existing = makeGroup(groupId = groupId, owner = owner)
        val (vm, _, _) = harness(
            outcome = JoinRequestSender.Outcome.Sent,
            initialActive = owner,
            seedGroups = listOf(existing),
        )
        // No send() — the watcher should pick up the existing group
        // and flip Ready → Approved on the first repository emission.
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("expected Approved, got $s", s is JoinViewModel.State.Approved)
        assertEquals(existing.id, (s as JoinViewModel.State.Approved).group.id)
    }

    @Test
    fun groupAppearsAfterSend_autoFlipsToApproved() = runTest(mainDispatcher.scheduler) {
        val owner = IdentityId("alice")
        val (vm, groupRepo, _) = harness(
            outcome = JoinRequestSender.Outcome.Sent,
            initialActive = owner,
        )

        vm.send("alice")
        advanceUntilIdle()
        assertTrue(vm.state.value is JoinViewModel.State.AwaitingApproval)

        // Simulate the sealed-invitation pipeline materializing the
        // group post-Approval.
        groupRepo.insert(makeGroup(groupId = groupId, owner = owner))
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("expected Approved after group insert, got $s", s is JoinViewModel.State.Approved)
    }

    @Test
    fun send_debouncesDoubleTap() = runTest(mainDispatcher.scheduler) {
        val (vm, _, callCount) = harness(outcome = JoinRequestSender.Outcome.Sent)

        vm.send("alice")
        // Second tap fires before the launched coroutine resolves.
        // With UnconfinedTestDispatcher the first send actually
        // resolves inline, so the second call hits the
        // "state isn't Ready/Failed anymore" guard rather than the
        // sendJob.isActive guard. Either way the contract is the
        // same — only one transport ship.
        vm.send("alice")
        advanceUntilIdle()

        assertEquals(1, callCount.value)
    }

    // ─── helpers ──────────────────────────────────────────────────

    /** Returns (vm, groupRepository, callCount) — callCount is a
     *  ref-count of how many times the stub `submitRequest` was
     *  invoked, for the debounce assertion. */
    private fun TestScope.harness(
        outcome: JoinRequestSender.Outcome,
        initialActive: IdentityId? = IdentityId("alice"),
        seedGroups: List<ChatGroup> = emptyList(),
    ): Triple<JoinViewModel, GroupRepository, IntRef> {
        val store = InMemoryGroupStore()
        kotlinx.coroutines.runBlocking { store.preload(seedGroups) }
        val active = FakeActiveIdentityProvider(initial = initialActive)
        val groupRepo = GroupRepository(store = store, identity = active, scope = this)
        kotlinx.coroutines.runBlocking { groupRepo.reload() }
        val calls = IntRef()
        val vm = JoinViewModel(
            capability = capability,
            submitRequest = { _, _ ->
                calls.value += 1
                outcome
            },
            groupRepository = groupRepo,
            suggestedDisplayLabel = "alice",
        )
        return Triple(vm, groupRepo, calls)
    }

    private fun makeGroup(groupId: ByteArray, owner: IdentityId): ChatGroup = ChatGroup(
        id = groupId.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        name = "Materialized group",
        groupSecret = ByteArray(32) { 0x55 },
        createdAtMillis = 1_700_000_000_000L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32) { 0x66 },
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
        ownerIdentityId = owner.value,
    )

    private class IntRef(var value: Int = 0)
}
