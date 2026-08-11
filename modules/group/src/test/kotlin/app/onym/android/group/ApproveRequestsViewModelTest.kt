package app.onym.android.group

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
import org.junit.Assert.assertNotEquals
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
    fun retryingClearsThatRequestsError() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1")))
        advanceUntilIdle()

        // First approve fails — produces an error against r1.
        approver.gate = CompletableDeferred()
        vm.approve("r1")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.TransportFailed("boom"))
        advanceUntilIdle()
        assertEquals("Couldn’t send: boom", vm.error("r1"))

        // Retrying clears it as the attempt STARTS, not when it lands —
        // otherwise the row renders the spinner over the previous
        // failure, which reads as a fresh one.
        approver.gate = CompletableDeferred()
        vm.approve("r1")
        runCurrent()
        assertNull(vm.error("r1"))

        approver.gate.complete(JoinRequestApprover.ApproveOutcome.Sent)
        advanceUntilIdle()
        // No success banner: the confirmation is the "Bob joined"
        // notice the approve path writes into the thread.
        assertNull(vm.error("r1"))
    }

    /** Two rows, two failures, two explanations. A single error slot
     *  could only ever hold the newer one, so the first row would go
     *  blank while its request was still there to retry. */
    @Test
    fun twoFailingRequestsEachKeepTheirOwnExplanation() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1"), samplePending("r2")))
        advanceUntilIdle()

        approver.gate = CompletableDeferred()
        vm.approve("r1")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.TransportFailed("boom"))
        advanceUntilIdle()

        approver.gate = CompletableDeferred()
        vm.approve("r2")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.NoActiveRelayer)
        advanceUntilIdle()

        assertNotNull(vm.error("r1"))
        assertNotNull(vm.error("r2"))
        assertEquals("Couldn’t send: boom", vm.error("r1"))
        assertNotEquals(vm.error("r1"), vm.error("r2"))
    }

    /** An error is otherwise only cleared by acting on that same
     *  request again — so a request that disappears while showing a
     *  failure left its entry behind for the process lifetime. */
    @Test
    fun errorsAreDroppedWhenTheirRequestStopsBeingPending() = runTest(dispatcher) {
        val approver = FakeApprover()
        val vm = ApproveRequestsViewModel(approver = approver)
        vm.start()
        approver.emit(listOf(samplePending("r1")))
        advanceUntilIdle()

        approver.gate = CompletableDeferred()
        vm.approve("r1")
        advanceUntilIdle()
        approver.gate.complete(JoinRequestApprover.ApproveOutcome.TransportFailed("boom"))
        advanceUntilIdle()
        assertNotNull(vm.error("r1"))

        // The request goes away without anyone acting on it.
        approver.emit(emptyList())
        advanceUntilIdle()

        assertNull(vm.error("r1"))
        assertTrue(vm.errors.value.isEmpty())
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
        assertEquals("Couldn’t send: relays down", vm.error("r1"))
    }

    private fun samplePending(id: String) = JoinRequestApprover.PendingRequest(
        id = id,
        joinerInboxPublicKey = ByteArray(32),
        joinerBlsPublicKey = null,
        joinerSendingPublicKey = ByteArray(32),
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
