package app.onym.android.moderation

import app.onym.android.moderation.support.FakeAuthorityClient
import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.InMemoryReportFilingStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import app.onym.android.moderation.support.fixtureMandateRecord
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * The report-filing lifecycle: verify locally, persist the signed
 * artifact before the wire, blobs before the report, and the
 * outcome taxonomy (replay, 409, deterministic discard, transient
 * keep). Mirrors iOS `ModerationRepositoryTests`' report section.
 */
class ModerationReportFilingTest {

    private val backend = ScriptedEnforcementBackendClient()
    private val attestation = FakeDeviceAttestationProvider()
    private val signer = FakeModerationSigner()
    private val mandateStore = InMemoryMandateStore(listOf(fixtureMandateRecord()))
    private val authority = FakeAuthorityClient()
    private val reportStore = InMemoryReportFilingStore()

    private fun repository(
        listing: AuthorityListing? = ModerationFixtures.listing(),
    ) = ModerationRepository(
        backend = backend,
        attestation = attestation,
        signer = signer,
        mandateStore = mandateStore,
        authority = authority,
        reportStore = reportStore,
        resolveAuthorityListing = { listing },
    )

    /** Real accused keypair: the local authenticity check verifies a
     * genuine Ed25519 signature, exactly as the authority will. */
    private val accusedKey = Ed25519KeyPairGenerator().let { generator ->
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        generator.generateKeyPair()
    }

    private fun evidence(
        disclosedContent: String = "{\"body\":\"abusive\",\"proof_version\":1}",
        messageId: String = "00000000-0000-0000-0000-000000000001",
        images: List<ReportableImage> = emptyList(),
    ): ReportableEvidence {
        val message = disclosedContent.toByteArray(Charsets.UTF_8)
        val signature = Ed25519Signer().apply {
            init(true, accusedKey.private as Ed25519PrivateKeyParameters)
            update(message, 0, message.size)
        }.generateSignature()
        val publicKey = (accusedKey.public as Ed25519PublicKeyParameters).encoded
        return ReportableEvidence(
            sourceMessageId = messageId,
            accused = keyReference(publicKey),
            disclosedContent = disclosedContent,
            authenticityProofBase64 = Base64.getEncoder().encodeToString(signature),
            images = images,
        )
    }

    @Test
    fun filesTheReport_persistsReceipt_uploadsBlobsFirst() = runTest {
        val image = ReportableImage(sha256 = "1a".repeat(32), bytes = ByteArray(9))
        val receipt = repository().fileReport(evidence(images = listOf(image)), "csam")

        assertEquals("case-fixture", receipt.caseId)
        assertEquals(listOf("1a".repeat(32) to 9), authority.uploadedBlobs)
        assertEquals(1, authority.filedReports.size)
        val stored = reportStore.records.single()
        assertEquals(receipt, stored.receipt)
        // The delivered bytes are the persisted artifact, verbatim.
        assertTrue(stored.report.wireBytes().contentEquals(authority.filedReports.single()))
        // The reporter cites their own mandate and signs the filing.
        assertEquals(fixtureMandateRecord().mandate.mandateHash(), stored.report.reporterMandate)
        assertEquals("onym:key:aabb", stored.report.reporter)
    }

    @Test
    fun refilingTheSameMessage_returnsTheStoredReceipt_withoutNetwork() = runTest {
        val repo = repository()
        val first = repo.fileReport(evidence(), "csam")
        val second = repo.fileReport(evidence(), "csam")

        assertEquals(first, second)
        assertEquals("one delivery, one replayed receipt", 1, authority.filedReports.size)
    }

    @Test
    fun conflictOnFiling_isTerminalAlreadyFiled_andSticks() = runTest {
        authority.reportFailure = AuthorityRejectedException(409, "case_state", "already on file")
        val repo = repository()
        try {
            repo.fileReport(evidence(), "csam")
            fail("expected AlreadyFiled")
        } catch (_: ReportFilingException.AlreadyFiled) {
        }
        assertTrue(reportStore.records.single().resolvedWithoutReceipt)

        // A later attempt short-circuits without another delivery.
        authority.reportFailure = null
        try {
            repo.fileReport(evidence(), "csam")
            fail("expected AlreadyFiled")
        } catch (_: ReportFilingException.AlreadyFiled) {
        }
        assertEquals(1, authority.filedReports.size)
    }

    @Test
    fun deterministicRejection_discardsTheArtifact_freshFilingReSigns() = runTest {
        authority.reportFailure =
            AuthorityRejectedException(422, "authenticity_unverified", "refused")
        val repo = repository()
        try {
            repo.fileReport(evidence(), "csam")
            fail("expected AuthorityRejectedException")
        } catch (_: AuthorityRejectedException) {
        }
        assertTrue("a filing replay can never fix must not linger", reportStore.records.isEmpty())

        authority.reportFailure = null
        repo.fileReport(evidence(), "csam")
        assertEquals(2, authority.filedReports.size)
        // Distinct artifacts: the discarded one was not replayed.
        val ids = authority.filedReports.map {
            ModerationJson.json.decodeFromString(
                ModerationReport.serializer(), it.decodeToString(),
            ).reportId
        }
        assertTrue(ids[0] != ids[1])
    }

    @Test
    fun transientFailure_keepsTheArtifact_retryReplaysTheExactBytes() = runTest {
        authority.reportFailure = AuthorityUnreachableException("offline")
        val repo = repository()
        try {
            repo.fileReport(evidence(), "csam")
            fail("expected AuthorityUnreachableException")
        } catch (_: AuthorityUnreachableException) {
        }
        assertEquals(1, reportStore.records.size)

        authority.reportFailure = null
        repo.fileReport(evidence(), "csam")
        assertEquals(2, authority.filedReports.size)
        assertTrue(
            "retry must replay byte-identically — a filed report is immutable",
            authority.filedReports[0].contentEquals(authority.filedReports[1]),
        )
    }

    @Test
    fun uploadConflict_isAgreement_theFilingProceeds() = runTest {
        authority.uploadFailure = AuthorityRejectedException(409, null, "already stored")
        val image = ReportableImage(sha256 = "1a".repeat(32), bytes = ByteArray(9))

        val receipt = repository().fileReport(evidence(images = listOf(image)), "csam")

        assertEquals("case-fixture", receipt.caseId)
        assertEquals(1, authority.filedReports.size)
    }

    @Test
    fun proofThatDoesNotVerify_isRefusedLocally_nothingDelivered() = runTest {
        val bad = evidence().copy(
            authenticityProofBase64 = Base64.getEncoder().encodeToString(ByteArray(64) { 1 }),
        )
        try {
            repository().fileReport(bad, "csam")
            fail("expected AuthenticityUnverified")
        } catch (_: ReportFilingException.AuthenticityUnverified) {
        }
        assertTrue(authority.filedReports.isEmpty())
        assertTrue(reportStore.records.isEmpty())
    }

    @Test
    fun classOutsideTheMandate_isRefused() = runTest {
        try {
            repository().fileReport(evidence(), "credible-violence")
            fail("expected ClassOutsideMandate")
        } catch (_: ReportFilingException.ClassOutsideMandate) {
        }
        assertTrue(authority.filedReports.isEmpty())
    }

    @Test
    fun unlistedAuthority_isUnavailable_nothingSignedOrStored() = runTest {
        try {
            repository(listing = null).fileReport(evidence(), "csam")
            fail("expected AuthorityUnavailable")
        } catch (_: ReportFilingException.AuthorityUnavailable) {
        }
        assertTrue(reportStore.records.isEmpty())
    }

    @Test
    fun mismatchedReceiptReportId_keepsTheArtifact_classifiedRetryable() = runTest {
        authority.reportReceiptOverride = ReportReceipt(reportId = "report-other", caseId = "case-x")
        try {
            repository().fileReport(evidence(), "csam")
            fail("expected AuthorityUnreachableException")
        } catch (_: AuthorityUnreachableException) {
        }
        assertEquals(1, reportStore.records.size)
        assertEquals(null, reportStore.records.single().receipt)
    }

    @Test
    fun availableReportClasses_isTheConsentedIntersection() = runTest {
        val classes = repository().availableReportClasses()
        assertEquals(listOf("csam"), classes.map { it.classId })
    }
}
