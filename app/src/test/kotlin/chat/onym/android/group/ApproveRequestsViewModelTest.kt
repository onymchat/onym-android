package chat.onym.android.group

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioral tests for [ApproveRequestsViewModel]. Drives a fake
 * [JoinRequestApproving] to assert the lifecycle / debounce / outcome
 * mapping without standing up the keychain + transport stack.
 *
 * Mirrors `ApproveRequestsFlowTests.swift` from onym-ios.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApproveRequestsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pendingMirrorsApproverFlow() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1")))
        advanceUntilIdle()
        assertEquals(1, vm.pending.value.size)
        assertEquals("r1", vm.pending.value[0].id)
    }

    @Test
    fun approveDebouncesSecondCallWhileInFlight() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1")))
        advanceUntilIdle()

        vm.approve("r1")
        advanceUntilIdle()
        assertTrue("first call must be in-flight", vm.inFlight.value.contains("r1"))
        assertEquals(1, approver.approveCallCount)

        vm.approve("r1")
        advanceUntilIdle()
        assertEquals("debounce: still 1", 1, approver.approveCallCount)

        approver.gate.complete(JoinRequestApprover.ApproveOutcome.Sent)
        advanceUntilIdle()
        assertTrue("inFlight cleared after completion", !vm.inFlight.value.contains("r1"))
    }

    @Test
    fun sentClearsLastError() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1")))
        advanceUntilIdle()

        // First approve fails — produces an error.
        approver.gate = CompletableDeferred()
        vm.approve("r1")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.TransportFailed("boom"))
        advanceUntilIdle()
        assertEquals("Couldn’t send: boom", vm.lastError.value)

        // Second approve succeeds — clears the error.
        approver.gate = CompletableDeferred()
        vm.approve("r1")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.Sent)
        // PR 91: the success banner auto-dismisses after 3s. Run
        // current work without advancing the virtual clock past
        // the dismiss timer so the banner is still set when we
        // assert.
        runCurrent()
        assertNull(vm.lastError.value)
        assertNotNull(vm.lastSuccessMessage.value)

        // After the timer fires, the banner clears.
        advanceTimeBy(3_001)
        runCurrent()
        assertNull(vm.lastSuccessMessage.value)
    }

    @Test
    fun transportFailedFormatsAsCouldntSend() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1")))
        advanceUntilIdle()

        approver.gate = CompletableDeferred()
        vm.approve("r1")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.TransportFailed("relays down"))
        advanceUntilIdle()
        assertEquals("Couldn’t send: relays down", vm.lastError.value)
    }

    private fun samplePending(id: String) = JoinRequestApprover.PendingRequest(
        id = id,
        joinerInboxPublicKey = ByteArray(32),
        joinerBlsPublicKey = null,
        joinerDisplayLabel = "Bob",
        groupId = ByteArray(32),
        groupName = "Family",
    )
}

/**
 * In-test [JoinRequestApproving] driver. Tests script the approver
 * behavior via [emit] (drives the `pending` flow) and [gate] (controls
 * when an approve call returns + with what outcome).
 */
private class FakeApprover : JoinRequestApproving {
    private val pendingState = MutableStateFlow<List<JoinRequestApprover.PendingRequest>>(emptyList())
    override val pending: StateFlow<List<JoinRequestApprover.PendingRequest>> =
        pendingState.asStateFlow()

    var approveCallCount: Int = 0
        private set
    var gate: CompletableDeferred<JoinRequestApprover.ApproveOutcome> = CompletableDeferred()

    override fun start() { /* no-op — pending is driven by the test */ }

    override suspend fun approve(requestId: String): JoinRequestApprover.ApproveOutcome {
        approveCallCount += 1
        return gate.await()
    }

    override suspend fun decline(requestId: String) { /* no-op */ }

    fun emit(snapshot: List<JoinRequestApprover.PendingRequest>) {
        pendingState.value = snapshot
    }
}
