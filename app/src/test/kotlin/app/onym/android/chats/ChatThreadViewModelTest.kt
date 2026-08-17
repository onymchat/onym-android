@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentityId
import app.onym.android.moderation.ReportableEvidence
import app.onym.android.moderation.ViolationClass
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.util.UUID

/**
 * Contract tests for [ChatThreadViewModel]. Verifies the data
 * plumbing the screen depends on:
 *  - `group` resolves the active group by id from
 *    [GroupRepository.snapshots].
 *  - `messages` mirrors [MessageRepository.snapshots] for the same id.
 *  - `send(body)` delegates to the captured interactor closure and
 *    routes errors into `lastSendError`; empty bodies short-circuit
 *    without invoking the closure.
 *  - `sendInFlight` flips around the closure execution.
 *
 * Mirrors `ChatThreadViewControllerTests.swift` from onym-ios PR #151
 * in spirit — iOS tests verify the UIKit controller wiring; Android
 * verifies the equivalent VM surface (the Compose screen is a thin
 * `collectAsStateWithLifecycle` reader over this VM).
 */
class ChatThreadViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val activeId = IdentityId("alice")
    private val groupIdHex = "aa".repeat(32)

    // ─── group resolution ────────────────────────────────────────

    @Test
    fun group_resolvesByGroupId_fromActiveIdentitySnapshots() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex, name = "Family")

        val vm = fixture.makeViewModel()
        assertEquals("Family", vm.group.first { it != null }?.name)
    }

    @Test
    fun group_nullWhenGroupNotPresent() = runTest {
        val fixture = newFixture()
        // No group seeded.
        val vm = fixture.makeViewModel()
        assertNull(vm.group.value)
    }

    // ─── message stream wiring ───────────────────────────────────

    @Test
    fun messages_minorOfMessageRepositorySnapshots() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val msg = sampleMessage(groupIdHex, body = "hi")
        fixture.messageStore.preload(listOf(msg))

        val vm = fixture.makeViewModel()
        val rows = vm.messages.first { it.isNotEmpty() }
        assertEquals(1, rows.size)
        assertEquals("hi", rows.single().body)
    }

    @Test
    fun messages_emitsOnAppend() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel()
        // Subscribe so MessageRepository's lazy cache materializes.
        assertEquals(0, vm.messages.first().size)

        fixture.messageRepository.append(sampleMessage(groupIdHex, body = "new"))
        val rows = vm.messages.first { it.isNotEmpty() }
        assertEquals("new", rows.single().body)
    }

    // ─── send action ─────────────────────────────────────────────

    @Test
    fun send_invokesInteractorWithTrimmedBody() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val captured = mutableListOf<Pair<String, String>>()
        val vm = fixture.makeViewModel(
            send = { gid, body, _ -> captured.add(gid to body) },
        )

        vm.send("  hello world  ")
        assertEquals(1, captured.size)
        assertEquals(groupIdHex to "hello world", captured.single())
    }

    @Test
    fun send_emptyOrWhitespaceBody_isShortCircuited() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val captured = mutableListOf<Pair<String, String>>()
        val vm = fixture.makeViewModel(
            send = { gid, body, _ -> captured.add(gid to body) },
        )

        vm.send("")
        vm.send("   ")
        assertTrue(
            "empty / whitespace bodies must not reach the interactor",
            captured.isEmpty(),
        )
    }

    @Test
    fun send_interactorThrows_routesIntoLastSendError() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            send = { _, _, _ -> throw SendMessageError.SenderNotAMember },
        )

        vm.send("hi")
        assertNotNull(vm.lastSendError.value)
    }

    @Test
    fun clearError_resetsLastSendError() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            send = { _, _, _ -> throw SendMessageError.EmptyBody },
        )
        vm.send("hi")
        assertNotNull(vm.lastSendError.value)

        vm.clearError()
        assertNull(vm.lastSendError.value)
    }

    @Test
    fun send_successClearsPreviousError() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        var shouldThrow = true
        val vm = fixture.makeViewModel(
            send = { _, _, _ ->
                if (shouldThrow) throw SendMessageError.SenderNotAMember
            },
        )
        vm.send("first")  // throws → error set
        assertNotNull(vm.lastSendError.value)

        shouldThrow = false
        vm.send("second")  // succeeds → error cleared
        assertNull(vm.lastSendError.value)
    }

    // ─── reply threading ─────────────────────────────────────────

    @Test
    fun armReply_setsReplyingTo_cancelClearsIt() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel()

        val target = UUID.randomUUID()
        vm.armReply(target)
        assertEquals(target, vm.replyingTo.value)

        vm.cancelReply()
        assertNull(vm.replyingTo.value)
    }

    @Test
    fun send_afterArming_forwardsReplyTarget_andClearsIt() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val captured = mutableListOf<Triple<String, String, UUID?>>()
        val vm = fixture.makeViewModel(
            send = { gid, body, replyTo -> captured.add(Triple(gid, body, replyTo)) },
        )

        val target = UUID.randomUUID()
        vm.armReply(target)
        vm.send("agreed")

        assertEquals(1, captured.size)
        assertEquals(Triple(groupIdHex, "agreed", target), captured.single())
        // Banner drops the moment the reply is sent.
        assertNull("send must clear the armed reply", vm.replyingTo.value)
    }

    @Test
    fun send_withoutArming_carriesNoReplyTarget() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        var capturedTarget: UUID? = UUID.randomUUID()  // sentinel
        val vm = fixture.makeViewModel(
            send = { _, _, replyTo -> capturedTarget = replyTo },
        )

        vm.send("plain")
        assertNull("a non-reply send must carry no target", capturedTarget)
    }

    @Test
    fun cancelReply_thenSend_carriesNoTarget() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        var capturedTarget: UUID? = UUID.randomUUID()  // sentinel
        val vm = fixture.makeViewModel(
            send = { _, _, replyTo -> capturedTarget = replyTo },
        )

        vm.armReply(UUID.randomUUID())
        vm.cancelReply()
        vm.send("plain")
        assertNull("after cancelling, a send must carry no reply target", capturedTarget)
    }

    // ─── retry action ────────────────────────────────────────────

    @Test
    fun retry_delegatesToInteractorClosureWithGroupIdAndMessageId() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val captured = mutableListOf<Pair<String, UUID>>()
        val vm = fixture.makeViewModel(
            retry = { gid, id -> captured.add(gid to id) },
        )

        val targetId = UUID.randomUUID()
        vm.retry(targetId)

        assertEquals(1, captured.size)
        assertEquals(groupIdHex to targetId, captured.single())
    }

    @Test
    fun retry_swallowsInteractorErrors() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            retry = { _, _ -> throw RuntimeException("transport down") },
        )
        // Must not propagate — retry surfaces failures as
        // MessageStatus.FAILED on the row, not via thrown exceptions.
        vm.retry(UUID.randomUUID())
    }

    // ─── report state machine ────────────────────────────────────

    /**
     * The report sheet is an async state machine over a UI the user
     * can back out of at any moment, which is exactly where a dialog
     * that reopens itself comes from: two of these cases (dismiss
     * mid-prepare, dismiss mid-submit) pin races that shipped
     * unobserved because this surface had no coverage at all.
     */

    private val reportEvidence = ReportableEvidence(
        sourceMessageId = "00000000-0000-0000-0000-000000000001",
        accused = "onym:key:" + "cc".repeat(32),
        disclosedContent = "{\"body\":\"abusive\",\"proof_version\":1}",
        authenticityProofBase64 = "c2ln",
    )

    private fun reportingHooks(
        isReportable: (ChatMessage, ChatGroup) -> Boolean = { _, _ -> true },
        prepare: suspend (ChatMessage, ChatGroup) -> ReportableEvidence? = { _, _ -> reportEvidence },
        classes: suspend () -> ReportClassesOutcome = {
            ReportClassesOutcome.Available(listOf(ViolationClass(classId = "csam")))
        },
        submit: suspend (ReportableEvidence, String) -> ReportSubmitOutcome = { _, _ ->
            ReportSubmitOutcome.Filed
        },
    ) = ChatReporting(
        isReportable = isReportable,
        prepare = prepare,
        classes = classes,
        submit = submit,
    )

    @Test
    fun beginReport_verifiesThenOffersTheConsentedClasses_withNoPreselection() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(reporting = reportingHooks())

        vm.beginReport(sampleMessage(groupIdHex, "abusive"))

        val form = vm.reportState.value as ReportUiState.Form
        assertEquals(listOf("csam"), form.classes.map { it.classId })
        assertNull("a preselected class invites mis-filings", form.selectedClassId)
        assertEquals("abusive", form.displayBody)
    }

    @Test
    fun dismissMidPrepare_neverReopensTheDialog() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(
                prepare = { _, _ ->
                    gate.await()
                    reportEvidence
                },
            ),
        )

        vm.beginReport(sampleMessage(groupIdHex, "abusive"))
        assertTrue(vm.reportState.value is ReportUiState.Preparing)

        // User backs out while the photo blob is still downloading.
        vm.dismissReport()
        assertNull(vm.reportState.value)

        // The prepare completing afterwards must stay silent — a form
        // popping open seconds later, on a message the user backed
        // out of, is the bug this pins.
        gate.complete(Unit)
        assertNull("a dismissed prepare must not resurrect the sheet", vm.reportState.value)
    }

    @Test
    fun dismissMidSubmit_neverReopensTheFiledSheet() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(
                submit = { _, _ ->
                    gate.await()
                    ReportSubmitOutcome.Filed
                },
            ),
        )
        vm.beginReport(sampleMessage(groupIdHex, "abusive"))
        vm.selectReportClass("csam")
        vm.submitReport()
        assertTrue((vm.reportState.value as ReportUiState.Form).submitting)

        vm.dismissReport()
        // The delivery is deliberately NOT cancelled — the artifact is
        // signed and persisted, and letting it land records the
        // receipt. Only the state write is suppressed.
        gate.complete(Unit)
        assertNull("a dismissed submit must not reopen \"Report filed\"", vm.reportState.value)
    }

    /**
     * The generation guard, at the case object identity cannot see:
     * `Preparing` is a `data object`, so prepare A and prepare B hold
     * the *same* instance and an identity check can't tell them
     * apart. A's cancelled job would then write its result over B's
     * `Preparing`, and B — finding a state it doesn't recognise —
     * would give up, leaving "this message can't be reported" on a
     * message that is perfectly reportable.
     */
    @Test
    fun aDismissedPrepareNeverClobbersTheNextOne() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        // A *controlled* dispatcher, unlike the rest of this suite:
        // the unconfined one resumes a cancelled job the instant
        // `cancel()` is called, so the stale write would land before
        // the next prepare even starts and the race would hide.
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        // Keyed by body, not popped from a queue: both prepares are in
        // flight at once, so a queue would be drained out of order.
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(
                prepare = { message, _ ->
                    if (message.body == "message A") {
                        // Swallows its own cancellation and returns
                        // normally. Cancelling A's job is therefore NOT
                        // enough on its own — the VM must not depend on
                        // an injected hook being cancellation-correct,
                        // and this is what makes the stale write
                        // actually reach the guard.
                        try {
                            gateA.await()
                        } catch (_: kotlinx.coroutines.CancellationException) {
                        }
                        null
                    } else {
                        gateB.await()
                        reportEvidence
                    }
                },
            ),
        )

        vm.beginReport(sampleMessage(groupIdHex, "message A"))
        runCurrent()                    // A reaches its gate
        vm.dismissReport()              // A cancelled; resumption queued
        vm.beginReport(sampleMessage(groupIdHex, "message B"))
        runCurrent()                    // A's stale write runs, then B starts

        assertTrue(
            "a retired prepare must not write over the live one",
            vm.reportState.value is ReportUiState.Preparing,
        )

        // B must still be able to complete into its own form.
        gateB.complete(Unit)
        runCurrent()
        val form = vm.reportState.value as ReportUiState.Form
        assertEquals("message B", form.displayBody)
    }

    @Test
    fun transientClassLoadFailure_isRetryable_notADeadEnd() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(classes = { ReportClassesOutcome.TransientFailure }),
        )

        vm.beginReport(sampleMessage(groupIdHex, "abusive"))

        // Store IO or a manifest that won't decode must not render as
        // "your authority offers no reportable classes" — that copy is
        // terminal and would send the user off to switch authorities.
        assertEquals(ReportUiState.Unavailable(ReportError.Transient), vm.reportState.value)
    }

    @Test
    fun beginReport_isReentrancyGuarded() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        var prepares = 0
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(prepare = { _, _ -> prepares++; reportEvidence }),
        )

        val message = sampleMessage(groupIdHex, "abusive")
        vm.beginReport(message)
        vm.beginReport(message)

        assertEquals("a second tap must not start a second prepare", 1, prepares)
    }

    @Test
    fun unverifiableMessage_reportsUnavailableWithoutAnActionableReason() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(prepare = { _, _ -> null }),
        )

        vm.beginReport(sampleMessage(groupIdHex, "abusive"))

        assertEquals(ReportUiState.Unavailable(null), vm.reportState.value)
    }

    @Test
    fun missingMandate_surfacesTheActionableCopy_notTheFlatRefusal() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(classes = { ReportClassesOutcome.MandateRequired }),
        )

        vm.beginReport(sampleMessage(groupIdHex, "abusive"))

        // "This message can't be reported" would hide a consent
        // problem the user can actually fix.
        assertEquals(
            ReportUiState.Unavailable(ReportError.MandateRequired),
            vm.reportState.value,
        )
    }

    @Test
    fun authorityWithNoReportableClasses_isDistinctFromAMissingMandate() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(classes = { ReportClassesOutcome.NoClasses }),
        )

        vm.beginReport(sampleMessage(groupIdHex, "abusive"))

        assertEquals(
            ReportUiState.Unavailable(ReportError.NoReportableClasses),
            vm.reportState.value,
        )
    }

    @Test
    fun submitOutcomes_mapToTerminalOrRecoverableStates() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val message = sampleMessage(groupIdHex, "abusive")

        val alreadyFiled = fixture.makeViewModel(
            reporting = reportingHooks(submit = { _, _ -> ReportSubmitOutcome.AlreadyFiled }),
        )
        alreadyFiled.beginReport(message)
        alreadyFiled.selectReportClass("csam")
        alreadyFiled.submitReport()
        assertEquals(ReportUiState.Done(alreadyFiled = true), alreadyFiled.reportState.value)

        // A failure keeps the form open, with the selection intact so
        // Submit resends the exact same signed report.
        val failed = fixture.makeViewModel(
            reporting = reportingHooks(
                submit = { _, _ -> ReportSubmitOutcome.Failed(ReportError.Delivery) },
            ),
        )
        failed.beginReport(message)
        failed.selectReportClass("csam")
        failed.submitReport()
        val form = failed.reportState.value as ReportUiState.Form
        assertEquals(ReportError.Delivery, form.error)
        assertEquals("csam", form.selectedClassId)
        assertTrue(!form.submitting)
    }

    @Test
    fun submitWithoutAClass_doesNothing() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        var submits = 0
        val vm = fixture.makeViewModel(
            reporting = reportingHooks(submit = { _, _ -> submits++; ReportSubmitOutcome.Filed }),
        )
        vm.beginReport(sampleMessage(groupIdHex, "abusive"))

        vm.submitReport()

        assertEquals(0, submits)
        assertTrue(vm.reportState.value is ReportUiState.Form)
    }

    @Test
    fun withoutTheModerationSeat_reportIsNeverOffered() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel()
        val group = vm.group.first { it != null }!!

        assertTrue(!vm.canReport(sampleMessage(groupIdHex, "abusive"), group))
        vm.beginReport(sampleMessage(groupIdHex, "abusive"))
        assertNull(vm.reportState.value)
    }

    // ─── helpers ─────────────────────────────────────────────────

    private fun sampleMessage(groupIdHex: String, body: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = groupIdHex,
        ownerIdentityId = activeId.value,
        senderBlsPubkeyHex = "cc".repeat(48),
        body = body,
        sentAtMillis = 1_700_000_000_000L,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.RECEIVED,
        groupType = SepGroupType.TYRANNY,
    )

    private fun newFixture(): Fixture {
        val activeProvider = FakeActiveIdentityProvider(initial = activeId)
        val groupStore = InMemoryGroupStore()
        val groupRepository = GroupRepository(
            store = groupStore,
            identity = activeProvider,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        groupRepository.start()
        val messageStore = InMemoryMessageStore()
        val messageRepository = MessageRepository(
            store = messageStore,
            identity = activeProvider,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        return Fixture(groupStore, groupRepository, messageStore, messageRepository)
    }

    private inner class Fixture(
        val groupStore: InMemoryGroupStore,
        val groupRepository: GroupRepository,
        val messageStore: InMemoryMessageStore,
        val messageRepository: MessageRepository,
    ) {
        suspend fun seedGroup(id: String, name: String = "Group") {
            groupStore.preload(
                listOf(
                    ChatGroup(
                        id = id,
                        name = name,
                        groupSecret = ByteArray(32),
                        createdAtMillis = 0L,
                        members = emptyList(),
                        memberProfiles = mapOf(
                            "aa".repeat(48) to MemberProfile(
                                alias = "Alice",
                                inboxPublicKey = ByteArray(32) { 0x22 },
                                sendingPubkey = ByteArray(32) { 0x33 },
                            ),
                        ),
                        epoch = 0uL,
                        salt = ByteArray(32),
                        commitment = null,
                        tier = SepTier.SMALL,
                        groupType = SepGroupType.TYRANNY,
                        adminPubkeyHex = null,
                        isPublishedOnChain = true,
                        ownerIdentityId = activeId.value,
                    ),
                ),
            )
            groupRepository.reload()
        }

        fun makeViewModel(
            send: suspend (String, String, UUID?) -> Unit = { _, _, _ -> },
            retry: suspend (String, UUID) -> Unit = { _, _ -> },
            reporting: ChatReporting? = null,
        ) = ChatThreadViewModel(
            groupId = groupIdHex,
            groupRepository = groupRepository,
            messageRepository = messageRepository,
            sendMessage = send,
            retryMessage = retry,
            reporting = reporting,
        )
    }
}
