package app.onym.android.moderation.ui

import app.onym.android.moderation.AppealReceipt
import app.onym.android.moderation.AuthorityRejectedException
import app.onym.android.moderation.AuthorityUnreachableException
import app.onym.android.moderation.CaseSubmissionStore
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.support.FakeAuthorityClient
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryCaseSubmissionStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import app.onym.android.moderation.support.fixtureMandateRecord
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appeal surface's state machine. What matters here is the retry
 * verdict: a refusal that exact resubmission cannot change must not
 * leave a live Submit button behind, and a refusal that CAN change must.
 */
class CaseAppealControllerTest {

    private val authority = FakeAuthorityClient()
    private val submissions = InMemoryCaseSubmissionStore()

    private fun repository(store: CaseSubmissionStore? = submissions) = ModerationRepository(
        backend = ScriptedEnforcementBackendClient(),
        attestation = FakeDeviceAttestationProvider(),
        signer = FakeModerationSigner(),
        mandateStore = InMemoryMandateStore(listOf(fixtureMandateRecord())),
        authority = authority,
        caseSubmissionStore = store,
        resolveAuthorityListing = { ModerationFixtures.listing() },
    )

    @Test
    fun submit_filesTheAppeal_andCarriesTheAuthoritysNote() = runTest {
        authority.appealReceiptOverride =
            AppealReceipt(caseId = "case-7", filed = true, kind = "appeal", note = "under review")
        val controller = controller()

        controller.submit("I did not send that.")

        val state = controller.state.value
        assertTrue(state is CaseAppealController.State.Filed)
        // The authority's own remark, never invented locally.
        assertEquals("under review", (state as CaseAppealController.State.Filed).note)
    }

    @Test
    fun submit_whileFiled_isANoOp() = runTest {
        val controller = controller()
        controller.submit("mine")
        assertTrue(controller.state.value is CaseAppealController.State.Filed)

        controller.submit("mine again")

        // One delivery: a second spinner over a filed appeal would be a
        // lie about what is happening.
        assertEquals(1, authority.filedAppeals.size)
    }

    /** Unreachable is the retryable case: the same signed bytes may yet
     *  land, and the repository kept them for exactly that. */
    @Test
    fun anUnreachableAuthority_leavesTheStatementEditableAndRetryable() = runTest {
        authority.appealFailure = AuthorityUnreachableException("offline")
        val controller = controller()

        controller.submit("mine")

        val state = controller.state.value as CaseAppealController.State.Editing
        assertEquals("offline", state.failure!!.reason)
        assertEquals(CaseAppealController.Recourse.RETRY, state.failure!!.recourse)
    }

    /** A closed appeal window cannot be reopened by resubmitting, and
     *  the authority's own words are the only useful thing to show. */
    @Test
    fun aDeterministicRefusal_isNotRetryable_andKeepsTheAuthoritysWording() = runTest {
        authority.appealFailure = AuthorityRejectedException(
            statusCode = 403,
            rawCode = "appeal_window_closed",
            message = "the appeal window for this case closed on 1 August",
        )
        val controller = controller()

        controller.submit("mine")

        val state = controller.state.value as CaseAppealController.State.Editing
        assertEquals("the appeal window for this case closed on 1 August", state.failure!!.reason)
        assertEquals(CaseAppealController.Recourse.NONE, state.failure!!.recourse)
    }

    /** 429 is transient: it is the one 4xx that exact replay can turn
     *  into an acceptance. */
    @Test
    fun rateLimiting_staysRetryable() = runTest {
        authority.appealFailure = AuthorityRejectedException(
            statusCode = 429,
            rawCode = "rate_limited",
            message = "slow down",
        )
        val controller = controller()

        controller.submit("mine")

        val state = controller.state.value as CaseAppealController.State.Editing
        assertEquals(CaseAppealController.Recourse.RETRY, state.failure!!.recourse)
    }

    @Test
    fun anInvalidStatement_isNotRetryable() = runTest {
        val controller = controller()

        controller.submit("   ")

        val state = controller.state.value as CaseAppealController.State.Editing
        assertTrue(state.failure!!.reason.contains("empty"))
        // EDIT, not NONE: a different statement genuinely gets a
        // different answer, so typing one must re-arm Submit.
        assertEquals(CaseAppealController.Recourse.EDIT, state.failure!!.recourse)
        assertTrue(authority.filedAppeals.isEmpty())
    }

    /**
     * The invariant this class owns, not the button's.
     *
     * After a terminal failure the repository has already DISCARDED the
     * artifact, so the ledger can no longer dedup — a resubmit would
     * file a genuine second appeal against an endpoint that does not
     * deduplicate. The screen disables Submit, but nothing stops another
     * caller, so the guard belongs here.
     */
    @Test
    fun submit_afterATerminalFailure_isANoOp() = runTest {
        authority.appealReceiptOverride =
            AppealReceipt(caseId = "case-OTHER", filed = true, kind = "appeal")
        val controller = controller()
        controller.submit("mine")
        assertEquals(
            CaseAppealController.Recourse.NONE,
            (controller.state.value as CaseAppealController.State.Editing).failure!!.recourse,
        )
        val deliveries = authority.filedAppeals.size

        controller.submit("mine")
        controller.submit("edited slightly")

        assertEquals(deliveries, authority.filedAppeals.size)
    }

    @Test
    fun submit_afterARetryableFailure_isAllowed() = runTest {
        authority.appealFailure = AuthorityUnreachableException("offline")
        val controller = controller()
        controller.submit("mine")

        authority.appealFailure = null
        controller.submit("mine")

        assertTrue(controller.state.value is CaseAppealController.State.Filed)
    }

    /** Dedup matches the EXACT statement, so a returning user who edits
     *  one character files a second appeal — the surface has to warn,
     *  because nothing downstream can catch it. */
    @Test
    fun priorFilings_surfacesAnAppealAlreadyOnFileForThisCase() = runTest {
        val repository = repository()
        repository.appeal("case-7", "filed earlier")

        val controller = CaseAppealController(
            caseId = "case-7",
            repository = repository,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        assertEquals(1, controller.priorFilings.value)
    }

    @Test
    fun priorFilings_ignoresOtherCases() = runTest {
        val repository = repository()
        repository.appeal("case-OTHER", "someone else's case")

        val controller = CaseAppealController(
            caseId = "case-7",
            repository = repository,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        assertEquals(0, controller.priorFilings.value)
    }

    @Test
    fun priorFilings_countsThisSurfacesOwnFiling() = runTest {
        val controller = controller()
        assertEquals(0, controller.priorFilings.value)

        controller.submit("mine")

        // So a rotation back onto the form warns rather than inviting a
        // silent second filing.
        assertEquals(1, controller.priorFilings.value)
    }


    /**
     * The bug: an unlisted authority was classified terminal, so the
     * screen told the user "filing this again would get the same answer"
     * about an outage that recovers on its own — and barred the retry
     * the repository keeps the artifact FOR. Nothing is even signed
     * before this throws on the fresh path.
     */
    @Test
    fun anUnlistedAuthority_isRetryable_notTerminal() = runTest {
        val controller = CaseAppealController(
            caseId = "case-7",
            repository = repositoryWithoutListing(),
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        controller.submit("mine")

        val state = controller.state.value as CaseAppealController.State.Editing
        assertEquals(CaseAppealController.Recourse.RETRY, state.failure!!.recourse)
    }

    /** Same reasoning for absent standing: a mandate registration that
     *  has not landed yet can land. */
    @Test
    fun absentStanding_isRetryable() = runTest {
        val controller = CaseAppealController(
            caseId = "case-7",
            repository = repository(store = null),
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        controller.submit("mine")

        val state = controller.state.value as CaseAppealController.State.Editing
        assertEquals(CaseAppealController.Recourse.RETRY, state.failure!!.recourse)
    }

    /**
     * The hole the previous test missed by never calling clearFailure:
     * every keystroke cleared the terminal failure, so one edited
     * character re-armed Submit after the artifact had been discarded —
     * routing around the guard in submit() instead of honouring it.
     */
    @Test
    fun editingAfterATerminalFailure_doesNotReArmSubmit() = runTest {
        authority.appealReceiptOverride =
            AppealReceipt(caseId = "case-OTHER", filed = true, kind = "appeal")
        val controller = controller()
        controller.submit("mine")
        val deliveries = authority.filedAppeals.size

        // What the screen does on every onValueChange.
        controller.clearFailure()

        val state = controller.state.value as CaseAppealController.State.Editing
        assertEquals(CaseAppealController.Recourse.NONE, state.failure!!.recourse)

        controller.submit("mine, edited")
        assertEquals(deliveries, authority.filedAppeals.size)
    }

    /** An EDIT failure is the one that SHOULD clear — the statement is
     *  what was refused. */
    @Test
    fun editingAfterAnInvalidStatement_reArmsSubmit() = runTest {
        val controller = controller()
        controller.submit("   ")
        assertEquals(
            CaseAppealController.Recourse.EDIT,
            (controller.state.value as CaseAppealController.State.Editing).failure!!.recourse,
        )

        controller.clearFailure()
        assertEquals(CaseAppealController.State.Editing(), controller.state.value)

        controller.submit("a real statement")
        assertTrue(controller.state.value is CaseAppealController.State.Filed)
    }

    /** A retryable failure clears on edit too — Submit was never barred
     *  for it. */
    @Test
    fun editingAfterARetryableFailure_clearsTheReason() = runTest {
        authority.appealFailure = AuthorityUnreachableException("offline")
        val controller = controller()
        controller.submit("mine")

        controller.clearFailure()

        assertEquals(CaseAppealController.State.Editing(), controller.state.value)
    }

    @Test
    fun clearFailure_dropsThePreviousAttemptsReason() = runTest {
        authority.appealFailure = AuthorityUnreachableException("offline")
        val controller = controller()
        controller.submit("mine")
        assertTrue((controller.state.value as CaseAppealController.State.Editing).failure != null)

        controller.clearFailure()

        assertEquals(
            CaseAppealController.State.Editing(),
            controller.state.value,
        )
    }

    /** Bytes, not characters: the cap the counter renders is the one the
     *  repository enforces, and Cyrillic reaches it twice as fast. */
    @Test
    fun statementBytes_countsUtf8OfTheTrimmedText() = runTest {
        val controller = controller()

        assertEquals(4, controller.statementBytes("  abcd  "))
        assertEquals(4, controller.statementBytes("яя"))
        assertEquals(ModerationRepository.MAX_STATEMENT_BYTES, controller.maxStatementBytes)
    }

    private fun repositoryWithoutListing() = ModerationRepository(
        backend = ScriptedEnforcementBackendClient(),
        attestation = FakeDeviceAttestationProvider(),
        signer = FakeModerationSigner(),
        mandateStore = InMemoryMandateStore(listOf(fixtureMandateRecord())),
        authority = authority,
        caseSubmissionStore = submissions,
        resolveAuthorityListing = { null },
    )

    private fun TestScope.controller() = CaseAppealController(
        caseId = "case-7",
        repository = repository(),
        // Unconfined so submit()'s launch runs to completion inline —
        // the assertions are about the settled state, not the schedule.
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
    )
}
