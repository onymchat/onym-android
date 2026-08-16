package app.onym.android.moderation.support

import app.onym.android.moderation.AttestationToken
import app.onym.android.moderation.AuthorityListing
import app.onym.android.moderation.AuthorityManifest
import app.onym.android.moderation.BackendRejectedException
import app.onym.android.moderation.BackendUnreachableException
import app.onym.android.moderation.DeviceAttestationProvider
import app.onym.android.moderation.DeviceEnrollment
import app.onym.android.moderation.EnforcementBackendClient
import app.onym.android.moderation.EnrollmentRequest
import app.onym.android.moderation.GateCheckRequest
import app.onym.android.moderation.GateCheckResult
import app.onym.android.moderation.GateStateStore
import app.onym.android.moderation.InterfaceCountersignature
import app.onym.android.moderation.IssuedChallenge
import app.onym.android.moderation.MandateRecord
import app.onym.android.moderation.MandateStore
import app.onym.android.moderation.ModerationJson
import app.onym.android.moderation.ModerationSigner
import app.onym.android.moderation.PersistedGateState
import app.onym.android.moderation.ReviewedManifest
import app.onym.android.moderation.SignedManifest
import app.onym.android.moderation.ViolationClass
import java.util.Base64

/** Fixed-token attestation — the fake for tests and (behind
 * `debugActive`) instrumented walk-throughs. */
class FakeDeviceAttestationProvider(
    var answer: AttestationToken = AttestationToken.Token("fixture-integrity-token"),
) : DeviceAttestationProvider {
    val requestHashes = mutableListOf<String>()

    override suspend fun requestToken(requestHash: String): AttestationToken {
        requestHashes += requestHash
        return answer
    }
}

/** Deterministic signer: 64 fixed bytes, a fixed key reference. The
 * backend fakes below never verify. */
class FakeModerationSigner(
    /** Mutable: identity-switch tests point the signer at another
     * identity mid-test, as `IdentityRepository.select` does. */
    var userKey: String = "onym:key:aabb",
) : ModerationSigner {
    val signedPayloads = mutableListOf<ByteArray>()

    override suspend fun userKeyId(): String = userKey

    override suspend fun sign(message: ByteArray): ByteArray {
        signedPayloads += message
        return ByteArray(64) { 7 }
    }
}

/**
 * Programmable enforcement backend. Enroll/countersign answer fixed
 * shapes; gate checks pop from [gateResults] (repeating the last
 * entry), or throw what [gateFailure] holds.
 */
class ScriptedEnforcementBackendClient : EnforcementBackendClient {
    var challengeCounter = 0

    /** When set, gateCheck suspends on it — lets concurrency tests
     * hold a check "on the wire" and observe coalescing. */
    var gateDelay: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var deviceBinding: String = "enrollment:fixture"
    var countersignature: String = Base64.getEncoder().encodeToString(ByteArray(64) { 9 })
    val gateResults = ArrayDeque<GateCheckResult>()
    var gateFailure: Exception? = null
    var lastGateRequest: GateCheckRequest? = null
    var lastEnrollment: EnrollmentRequest? = null
    var lastCountersignedBytes: ByteArray? = null

    override suspend fun fetchChallenge(purpose: String): IssuedChallenge {
        challengeCounter += 1
        // 32 distinct bytes per issue, like the real backend.
        val raw = ByteArray(32) { (it + challengeCounter).toByte() }
        return IssuedChallenge(
            challenge = Base64.getEncoder().encodeToString(raw),
            expiresAt = "2100-01-01T00:00:00Z",
        )
    }

    /** When set, enrollDevice throws it — consent-failure tests. */
    var enrollFailure: Exception? = null

    /** When set, enrollDevice suspends on it — mid-transaction
     * cancellation tests. */
    var enrollGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun enrollDevice(request: EnrollmentRequest): DeviceEnrollment {
        lastEnrollment = request
        enrollGate?.await()
        enrollFailure?.let { throw it }
        return DeviceEnrollment(deviceBinding)
    }

    override suspend fun countersignMandate(
        mandateWireBytes: ByteArray,
    ): InterfaceCountersignature {
        lastCountersignedBytes = mandateWireBytes
        return InterfaceCountersignature(countersignature)
    }

    override suspend fun gateCheck(request: GateCheckRequest): GateCheckResult {
        lastGateRequest = request
        gateDelay?.await()
        gateFailure?.let { throw it }
        return when (gateResults.size) {
            0 -> GateCheckResult.Clear
            1 -> gateResults.first()
            else -> gateResults.removeFirst()
        }
    }

    fun failWith(statusCode: Int, rawCode: String?, message: String = "refused") {
        gateFailure = BackendRejectedException(statusCode, rawCode, message)
    }

    fun failUnreachable() {
        gateFailure = BackendUnreachableException("fixture offline")
    }
}

class InMemoryGateStateStore(private var state: PersistedGateState? = null) : GateStateStore {
    override suspend fun load(): PersistedGateState? = state
    override suspend fun save(state: PersistedGateState?) {
        this.state = state
    }
}

class InMemoryMandateStore(private var records: List<MandateRecord> = emptyList()) : MandateStore {
    override suspend fun load(): List<MandateRecord> = records
    override suspend fun save(records: List<MandateRecord>) {
        this.records = records
    }
}

/** Fixed directory: the fixture listing, or a scripted failure. */
class FakeKnownAuthoritiesFetcher(
    var listings: List<AuthorityListing> = listOf(ModerationFixtures.listing()),
    var failure: Exception? = null,
) : app.onym.android.moderation.KnownAuthoritiesFetcher {
    override suspend fun fetchLatest(): List<AuthorityListing> {
        failure?.let { throw it }
        return listings
    }
}

/** Serves the fixture signed manifest without network or signature
 * verification (the real fetcher's verification has its own tests). */
class FakeAuthorityManifestFetcher(
    var signed: SignedManifest = ModerationFixtures.signedManifest(),
    var failure: Exception? = null,
) : app.onym.android.moderation.AuthorityManifestFetcher {
    override suspend fun fetch(listing: AuthorityListing): SignedManifest {
        failure?.let { throw it }
        return signed
    }
}

/** A consented-manifest fixture: exact bytes and their decoded view,
 * skipping the fetcher (whose signature verification has its own
 * tests). */
object ModerationFixtures {
    const val AUTHORITY_ID: String = "onym:component:test-authority"

    fun listing(): AuthorityListing = AuthorityListing(
        componentId = AUTHORITY_ID,
        name = "Test Authority",
        manifestURL = "https://authority.example/manifest.json",
        apiBaseURL = "https://authority.example",
        operatorPublicKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 3 }),
    )

    fun manifest(validUntil: String = "2100-01-01T00:00:00Z"): AuthorityManifest =
        AuthorityManifest(
            componentId = AUTHORITY_ID,
            operatorKey = "onym:key:" + "03".repeat(32),
            moderationProfileId =
                "onym:moderation-enforcement-profile:google-device-recall-v1",
            violationClasses = listOf(
                ViolationClass(
                    classId = "csam",
                    definition = "hash-or-url",
                    responseWindow = "P3D",
                    decisionDeadline = "P7D",
                    banTerm = "P30D",
                    appealWindow = "P14D",
                    appealEffect = "suspensive",
                ),
            ),
            validUntil = validUntil,
        )

    fun signedManifest(validUntil: String = "2100-01-01T00:00:00Z"): SignedManifest {
        val manifest = manifest(validUntil)
        val raw = ModerationJson.json
            .encodeToString(AuthorityManifest.serializer(), manifest)
            .encodeToByteArray()
        return SignedManifest.forFixtures(manifest, raw)
    }

    fun reviewedManifest(validUntil: String = "2100-01-01T00:00:00Z"): ReviewedManifest =
        ReviewedManifest(signedManifest(validUntil))
}

/** A ready-made active mandate record, for tests that boot straight
 * into a mandated state without walking consent. */
fun fixtureMandateRecord(
    userKey: String = "onym:key:aabb",
    deviceBinding: String = "enrollment:fixture",
): MandateRecord {
    val signed = ModerationFixtures.signedManifest()
    val mandate = app.onym.android.moderation.ModerationMandate(
        user = userKey,
        interface_ = "onym:component:onym-android",
        authority = ModerationFixtures.AUTHORITY_ID,
        manifestHash = signed.manifestHash,
        classes = listOf("csam"),
        deviceBinding = deviceBinding,
        acceptedAt = "2026-08-08T00:00:00Z",
        signatures = listOf(
            Base64.getEncoder().encodeToString(ByteArray(64) { 7 }),
            Base64.getEncoder().encodeToString(ByteArray(64) { 9 }),
        ),
    )
    return MandateRecord(
        mandate = mandate,
        manifestBytesBase64 = Base64.getEncoder().encodeToString(signed.rawBytes),
        authorityName = "Test Authority",
        countersigned = true,
        authorityRegistered = false,
        isActive = true,
        createdAt = "2026-08-08T00:00:00Z",
    )
}
