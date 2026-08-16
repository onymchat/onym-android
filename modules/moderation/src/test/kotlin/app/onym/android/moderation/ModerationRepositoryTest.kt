package app.onym.android.moderation

import app.onym.android.moderation.support.FakeDeviceAttestationProvider
import app.onym.android.moderation.support.FakeModerationSigner
import app.onym.android.moderation.support.InMemoryMandateStore
import app.onym.android.moderation.support.ModerationFixtures
import app.onym.android.moderation.support.ScriptedEnforcementBackendClient
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModerationRepositoryTest {
    private val backend = ScriptedEnforcementBackendClient()
    private val attestation = FakeDeviceAttestationProvider()
    private val signer = FakeModerationSigner()
    private val store = InMemoryMandateStore()
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z")

    private fun repository() = ModerationRepository(
        backend = backend,
        attestation = attestation,
        signer = signer,
        mandateStore = store,
        clock = { now },
    )

    @Test
    fun `consent enrolls signs countersigns and persists an active record`() = runTest {
        val record = repository().consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )

        // Enrollment carried the challenge, the token, and a signature
        // over the enrollment payload (the first signed payload).
        val enrollment = backend.lastEnrollment
        assertNotNull(enrollment)
        assertEquals("onym:key:aabb", enrollment!!.userKey)
        assertEquals("fixture-integrity-token", enrollment.integrityToken)
        assertEquals(1, attestation.requestHashes.size)
        // The requestHash the token bound is derived from the same
        // payload the signature covers.
        assertEquals(
            SignedSessionPayload.requestHash(signer.signedPayloads.first()),
            attestation.requestHashes.first(),
        )

        // The mandate pins the reviewed manifest's exact-bytes hash
        // and the issued binding, and carries both signatures.
        assertEquals(ModerationFixtures.signedManifest().manifestHash, record.mandate.manifestHash)
        assertEquals("enrollment:fixture", record.mandate.deviceBinding)
        assertEquals(listOf("csam"), record.mandate.classes)
        assertEquals("onym:component:onym-android", record.mandate.interface_)
        assertEquals(2, record.mandate.signatures.size)
        assertTrue(record.countersigned)
        assertTrue(record.isActive)

        // The countersigned bytes were the client's own mandate copy.
        val sent = backend.lastCountersignedBytes!!.decodeToString()
        assertTrue(sent, sent.contains("\"interface\":\"onym:component:onym-android\""))

        // Persisted, and readable as the active record.
        assertEquals(record, repository().activeMandateRecord())
    }

    @Test
    fun `a lapsed manifest refuses consent before any network hop`() = runTest {
        try {
            repository().consent(
                ModerationFixtures.listing(),
                ModerationFixtures.reviewedManifest(validUntil = "2020-01-01T00:00:00Z"),
            )
            fail("a lapsed manifest must not consent")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!.contains("validity"))
        }
        assertEquals(0, backend.challengeCounter)
    }

    @Test
    fun `a garbage countersignature does not mint a record`() = runTest {
        backend.countersignature = "bm90LWEtc2ln"
        try {
            repository().consent(
                ModerationFixtures.listing(),
                ModerationFixtures.reviewedManifest(),
            )
            fail("a non-64-byte countersignature must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!.contains("countersignature"))
        }
        assertEquals(null, repository().activeMandateRecord())
    }

    @Test
    fun `a throttled provider refuses consent retryably`() = runTest {
        attestation.answer = AttestationToken.Throttled
        try {
            repository().consent(
                ModerationFixtures.listing(),
                ModerationFixtures.reviewedManifest(),
            )
            fail("throttled attestation must not enroll")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!.contains("rate-limited"))
        }
    }

    @Test
    fun `an unsupported environment enrolls without a token`() = runTest {
        attestation.answer = AttestationToken.Unsupported
        repository().consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )
        assertEquals(null, backend.lastEnrollment!!.integrityToken)
    }

    /**
     * The Huawei-class case: no GMS locally AND a production backend
     * refusing the token-less enrollment. Typed as unsupported-device
     * (the rail cannot exist on this hardware, §8.5) so consent
     * surfaces defer instead of looping a retry that cannot change
     * the hardware. A refusal WITH a token stays an ordinary
     * BackendRejectedException — that device was judged.
     */
    @Test
    fun `a refused token-less enrollment is typed as unsupported hardware`() = runTest {
        attestation.answer = AttestationToken.Unsupported
        backend.enrollFailure = BackendRejectedException(
            400,
            null,
            "integrityToken is required when Play Integrity is configured",
        )
        try {
            repository().consent(
                ModerationFixtures.listing(),
                ModerationFixtures.reviewedManifest(),
            )
            fail("the refusal must surface")
        } catch (e: ModerationUnsupportedDeviceException) {
            assertTrue(e.message!!.contains("Google Play services"))
        }

        // With a token, the same refusal stays an ordinary rejection.
        attestation.answer = AttestationToken.Token("fixture-integrity-token")
        try {
            repository().consent(
                ModerationFixtures.listing(),
                ModerationFixtures.reviewedManifest(),
            )
            fail("the refusal must surface")
        } catch (e: BackendRejectedException) {
            assertTrue(e !is ModerationUnsupportedDeviceException)
        }
    }

    @Test
    fun `a fresh consent deactivates the previous record`() = runTest {
        val repository = repository()
        val first = repository.consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )
        now = now.plusSeconds(3600)
        val second = repository.consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )
        val records = repository.snapshots.value.records
        assertEquals(2, records.size)
        assertEquals(second.mandate.acceptedAt, records.first().mandate.acceptedAt)
        assertTrue(records.first().isActive)
        val previous = records.first { it.mandate.acceptedAt == first.mandate.acceptedAt }
        assertTrue(!previous.isActive)
    }

    /**
     * The identity-switch brick: resolving "the active mandate" with
     * no identity filter sent identity A's userKey while identity B
     * signed — signature_invalid, a STICKY backendRefused, cleared
     * only by a success that could never come. The active record is
     * per-identity: a switch resolves NotMandated (-> consent), a
     * switch back finds the original record, and a fresh consent
     * deactivates only the same identity's previous record.
     */
    @Test
    fun `switching identity resolves that identity's mandate or none`() = runTest {
        val repository = repository()
        val recordA = repository.consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )
        assertEquals(recordA, repository.activeMandateRecord())

        // Switch to an unmandated identity: no record, never a
        // mismatched session.
        signer.userKey = "onym:key:ccdd"
        assertEquals(null, repository.activeMandateRecord())

        // The new identity consents; both identities now hold their
        // own active records.
        now = now.plusSeconds(3600)
        backend.deviceBinding = "enrollment:fixture-b"
        val recordB = repository.consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )
        assertEquals(recordB, repository.activeMandateRecord())
        assertEquals("onym:key:ccdd", recordB.mandate.user)

        // Switching back finds A's record, still active.
        signer.userKey = "onym:key:aabb"
        assertEquals(recordA, repository.activeMandateRecord())
    }

    /** The user signature travels base64 over the canonical bytes the
     * backend will re-derive. */
    @Test
    fun `the mandate user signature covers the signing bytes`() = runTest {
        repository().consent(
            ModerationFixtures.listing(),
            ModerationFixtures.reviewedManifest(),
        )
        // signedPayloads: [enrollment payload, mandate signing bytes]
        assertEquals(2, signer.signedPayloads.size)
        val mandateBytes = signer.signedPayloads[1].decodeToString()
        assertTrue(mandateBytes, mandateBytes.startsWith("{\"acceptedAt\":"))
        assertTrue(mandateBytes, !mandateBytes.contains("signatures"))
        // And the recorded signature is the fake signer's output.
        val record = repository().activeMandateRecord()!!
        assertEquals(
            Base64.getEncoder().encodeToString(ByteArray(64) { 7 }),
            record.mandate.signatures.first(),
        )
    }
}
