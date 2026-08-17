package app.onym.android.moderation

import app.onym.android.moderation.support.FakeAuthorityClient
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryCaseSubmissionStore
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import app.onym.android.moderation.support.fixtureMandateRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * The appeal-filing lifecycle: sign, persist the artifact BEFORE the
 * wire, and classify the outcome by what a retry could change.
 *
 * The appeal endpoint does not deduplicate server-side — each delivery
 * files a row — so the ledger checks here are not an optimisation, they
 * are the only thing standing between a double-tap and two filings.
 * Mirrors iOS `ModerationRepositoryTests`' appeal section.
 */
class ModerationAppealFilingTest {

    private val backend = ScriptedEnforcementBackendClient()
    private val attestation = FakeDeviceAttestationProvider()
    private val signer = FakeModerationSigner()
    private val mandateStore = InMemoryMandateStore(listOf(fixtureMandateRecord()))
    private val authority = FakeAuthorityClient()
    private val submissions = InMemoryCaseSubmissionStore()

    private fun repository(
        listing: AuthorityListing? = ModerationFixtures.listing(),
        store: CaseSubmissionStore? = submissions,
    ) = ModerationRepository(
        backend = backend,
        attestation = attestation,
        signer = signer,
        mandateStore = mandateStore,
        authority = authority,
        caseSubmissionStore = store,
        resolveAuthorityListing = { listing },
    )

    @Test
    fun appeal_filesTheSignedStatement_andRecordsTheReceipt() = runTest {
        val receipt = repository().appeal("case-7", "  I did not send that.  ")

        assertEquals("case-7", receipt.caseId)
        assertTrue(receipt.filed)
        assertEquals(1, authority.filedAppeals.size)
        assertEquals("case-7", authority.filedAppeals.single().first)

        val delivered = ModerationJson.json.decodeFromString(
            AppealSubmission.serializer(),
            authority.filedAppeals.single().second.decodeToString(),
        )
        // Trimmed, and signed — the authority verifies over the
        // canonical unsigned form, so an empty signature would be
        // refused rather than merely unattributed.
        assertEquals("I did not send that.", delivered.statement)
        assertEquals(AppealSubmission.Kind.APPEAL, delivered.kind)
        assertTrue(delivered.signature.isNotEmpty())

        val row = submissions.load().single()
        assertNotNull(row.receipt)
        // Resolved rows are receipt stubs: the user's account of an
        // accusation does not stay on disk once it has landed.
        assertEquals("", row.appeal.statement)
    }

    /** The signature covers every field but `signature` — and `caseId`
     *  is one of them, which is what stops an appeal captured for one
     *  case being re-pathed at another. */
    @Test
    fun signingBytes_coverCaseIdKindAndStatement_butNotTheSignature() {
        val appeal = AppealSubmission(
            caseId = "case-7",
            kind = AppealSubmission.Kind.APPEAL,
            statement = "mine",
        )
        val bytes = appeal.signingBytes().decodeToString()

        assertEquals("""{"caseId":"case-7","kind":"appeal","statement":"mine"}""", bytes)
        // Signing is stable under the signature being filled in.
        assertEquals(bytes, appeal.copy(signature = "AAAA").signingBytes().decodeToString())
        // A different case is different bytes.
        assertTrue(appeal.copy(caseId = "case-8").signingBytes().decodeToString() != bytes)
    }

    @Test
    fun appeal_sameCaseAndStatementTwice_returnsTheStoredReceipt_withoutASecondDelivery() =
        runTest {
            val repository = repository()
            val first = repository.appeal("case-7", "I did not send that.")
            val second = repository.appeal("case-7", "  I did not send that.")

            assertEquals(first, second)
            // The authority files each delivery it receives; one
            // delivery is the whole point of the ledger check.
            assertEquals(1, authority.filedAppeals.size)
            assertEquals(1, submissions.load().size)
        }

    @Test
    fun appeal_aDifferentStatement_isASecondFiling() = runTest {
        val repository = repository()
        repository.appeal("case-7", "I did not send that.")
        repository.appeal("case-7", "Here is more context.")

        assertEquals(2, authority.filedAppeals.size)
        assertEquals(2, submissions.load().size)
    }

    /** A statement already acknowledged answers from the ledger even
     *  with the authority unlisted — otherwise re-opening the screen
     *  offline would report "the authority is not in the directory"
     *  about a filing that already landed. */
    @Test
    fun appeal_alreadyFiled_answersFromTheLedgerWithNoDirectoryListing() = runTest {
        repository().appeal("case-7", "mine")

        val receipt = repository(listing = null).appeal("case-7", "mine")

        assertEquals("case-7", receipt.caseId)
        assertEquals(1, authority.filedAppeals.size)
    }

    @Test
    fun appeal_persistsTheArtifactBeforeDelivering_soATransientFailureCanReplayIt() = runTest {
        authority.appealFailure = AuthorityUnreachableException("offline")

        try {
            repository().appeal("case-7", "mine")
            fail("expected the delivery to fail")
        } catch (_: AuthorityUnreachableException) {
        }

        // Kept, unresolved, statement intact — that is what a retry
        // re-delivers, byte-identically rather than re-signed.
        val row = submissions.load().single()
        assertNull(row.receipt)
        assertEquals("mine", row.appeal.statement)

        authority.appealFailure = null
        val retried = repository().appeal("case-7", "mine")
        assertEquals("case-7", retried.caseId)
        assertEquals(2, authority.filedAppeals.size)
        // Byte-identical: the retry replayed the artifact.
        assertEquals(
            authority.filedAppeals[0].second.decodeToString(),
            authority.filedAppeals[1].second.decodeToString(),
        )
        assertNotNull(submissions.load().single().receipt)
    }

    /** Outside the appeal window, or a case already decided: replay can
     *  never turn that into an acceptance, so keeping the artifact
     *  would just deadlock the case behind a filing that cannot land. */
    @Test
    fun appeal_deterministicRefusal_discardsTheArtifact() = runTest {
        authority.appealFailure = AuthorityRejectedException(
            statusCode = 403,
            rawCode = "appeal_window_closed",
            message = "the appeal window closed",
        )

        try {
            repository().appeal("case-7", "mine")
            fail("expected a rejection")
        } catch (e: AuthorityRejectedException) {
            assertEquals(403, e.statusCode)
        }

        assertTrue(submissions.load().isEmpty())
    }

    @Test
    fun appeal_rateLimited_keepsTheArtifact() = runTest {
        authority.appealFailure = AuthorityRejectedException(
            statusCode = 429,
            rawCode = "rate_limited",
            message = "slow down",
        )

        try {
            repository().appeal("case-7", "mine")
            fail("expected a rejection")
        } catch (_: AuthorityRejectedException) {
        }

        // 429 is the transient pair with 408: exact replay may yet be
        // accepted, so the signed artifact stays.
        assertEquals(1, submissions.load().size)
        assertNull(submissions.load().single().receipt)
    }

    /**
     * A receipt for another case means the authority accepted
     * SOMETHING — this endpoint answers 200 and files a row — so the
     * artifact must not survive as retryable: a "retry" would file a
     * second appeal rather than resolve the first.
     */
    @Test
    fun appeal_receiptForAnotherCase_isTerminalAndDiscardsTheArtifact() = runTest {
        authority.appealReceiptOverride =
            AppealReceipt(caseId = "case-OTHER", filed = true, kind = "appeal")

        try {
            repository().appeal("case-7", "mine")
            fail("expected a correlation failure")
        } catch (e: AppealFilingException.AcknowledgedDifferentSubmission) {
            assertTrue(e.message!!.contains("case-OTHER"))
        }

        assertTrue(submissions.load().isEmpty())
    }

    @Test
    fun appeal_receiptForAnotherKind_isTerminal() = runTest {
        authority.appealReceiptOverride =
            AppealReceipt(caseId = "case-7", filed = true, kind = "new-holder-claim")

        try {
            repository().appeal("case-7", "mine")
            fail("expected a correlation failure")
        } catch (e: AppealFilingException.AcknowledgedDifferentSubmission) {
            assertTrue(e.message!!.contains("new-holder-claim"))
        }

        assertTrue(submissions.load().isEmpty())
    }

    /**
     * `filed: false` on a 200 is nonconforming — a refusal is supposed
     * to be a 4xx — so it must never be stored as resolved: that would
     * redact the statement and render "Appeal filed" over an authority
     * that just said otherwise.
     */
    @Test
    fun appeal_twoHundredWithFiledFalse_isTerminalAndNotRecordedAsFiled() = runTest {
        authority.appealReceiptOverride = AppealReceipt(
            caseId = "case-7",
            filed = false,
            kind = "appeal",
            note = "no open appeal window",
        )

        try {
            repository().appeal("case-7", "mine")
            fail("filed=false must not be reported as success")
        } catch (e: AppealFilingException.RefusedWithoutFiling) {
            // The authority's own remark carries through — it is the
            // only party that knows why.
            assertTrue(e.message!!.contains("no open appeal window"))
        }

        // Discarded, not left armed for replay: we cannot rule out that
        // it WAS filed, and this endpoint does not deduplicate.
        assertTrue(submissions.load().isEmpty())
    }

    @Test
    fun appeal_emptyOrWhitespaceStatement_isRefusedLocally() = runTest {
        try {
            repository().appeal("case-7", "   \n ")
            fail("expected the statement to be refused")
        } catch (_: AppealFilingException.StatementInvalid) {
        }
        assertTrue(authority.filedAppeals.isEmpty())
        assertTrue(submissions.load().isEmpty())
    }

    @Test
    fun appeal_statementOverTheByteCap_isRefusedLocally() = runTest {
        // Cyrillic: two bytes per character, so this is over the cap at
        // half the character count — which is why the cap is in bytes.
        val statement = "я".repeat(ModerationRepository.MAX_STATEMENT_BYTES / 2 + 1)
        try {
            repository().appeal("case-7", statement)
            fail("expected the statement to be refused")
        } catch (e: AppealFilingException.StatementInvalid) {
            assertTrue(e.message!!.contains("exceeds"))
        }
        assertTrue(authority.filedAppeals.isEmpty())
    }

    @Test
    fun appeal_withNoLedgerWired_refusesRatherThanDeliveringUnretained() = runTest {
        try {
            repository(store = null).appeal("case-7", "mine")
            fail("expected the vertical to be dark")
        } catch (_: AppealFilingException.AppealsUnavailable) {
        }
        assertTrue(authority.filedAppeals.isEmpty())
    }

    @Test
    fun appeal_withNoDirectoryListing_refusesBeforeSigning() = runTest {
        try {
            repository(listing = null).appeal("case-7", "mine")
            fail("expected the authority to be unavailable")
        } catch (_: AppealFilingException.AuthorityUnavailable) {
        }
        // Nothing signed, nothing retained: there is no artifact to
        // deliver anywhere yet.
        assertTrue(submissions.load().isEmpty())
    }

    /**
     * Dedup is scoped to `mandateRef`, so an old mandate's UNRESOLVED
     * row can never be matched or replayed again — nothing will look it
     * up — while still holding the statement in plaintext until the
     * 16-row bound evicts it. It is purged instead.
     */
    @Test
    fun appeal_purgesUnresolvedRowsFiledUnderAnotherMandate() = runTest {
        authority.appealFailure = AuthorityUnreachableException("offline")
        try {
            repository().appeal("case-7", "written under the old mandate")
            fail("expected the delivery to fail")
        } catch (_: AuthorityUnreachableException) {
        }
        val orphan = submissions.load().single()
        assertNull(orphan.receipt)

        // Same row, restamped as if a different mandate had filed it.
        submissions.save(listOf(orphan.copy(mandateRef = "some-other-mandate")))
        authority.appealFailure = null

        repository().appeal("case-7", "written under the new mandate")

        // The orphan is gone; only the new filing remains.
        val rows = submissions.load()
        assertEquals(1, rows.size)
        assertEquals("some-other-mandate" != rows.single().mandateRef, true)
        assertNotNull(rows.single().receipt)
    }

    /** RESOLVED rows survive a mandate rotation: they hold no statement
     *  (already redacted) and they are what warns a returning user that
     *  this case already has a filing — which must outlive the rotation,
     *  since the authority's copy does. */
    @Test
    fun appeal_keepsResolvedRowsFromAnotherMandate() = runTest {
        repository().appeal("case-7", "filed and acknowledged")
        val resolved = submissions.load().single()
        assertNotNull(resolved.receipt)
        submissions.save(listOf(resolved.copy(mandateRef = "some-other-mandate")))

        repository().appeal("case-7", "a fresh statement")

        assertEquals(2, submissions.load().size)
        assertEquals(2, repository().appeals("case-7").size)
    }

    @Test
    fun appeals_listsTheCasesOwnFilings() = runTest {
        val repository = repository()
        repository.appeal("case-7", "mine")
        repository.appeal("case-8", "other")

        assertEquals(1, repository.appeals("case-7").size)
        assertEquals("case-7", repository.appeals("case-7").single().appeal.caseId)
        assertTrue(repository.appeals("case-nonexistent").isEmpty())
    }
}
