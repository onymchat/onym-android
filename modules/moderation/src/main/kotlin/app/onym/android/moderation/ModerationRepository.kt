package app.onym.android.moderation

import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the gate flow reads from the consent side. */
data class ModerationState(
    val loaded: Boolean = false,
    val records: List<MandateRecord> = emptyList(),
) {
    /** The active record FOR ONE IDENTITY. Never resolve an active
     * record without naming whose: the device can hold several
     * identities ([app.onym.android.identity.IdentityRepository.select]
     * is a Settings surface), each with its own mandate, and a gate
     * check that sends identity A's `userKey` while identity B signs
     * the session is a `signature_invalid` refusal — sticky, and
     * clearable only by a success that can never come. */
    fun activeMandate(userKey: String): MandateRecord? =
        records.firstOrNull { it.isActive && it.mandate.user == userKey }
}

/**
 * Owns mandate consent: the minimal Android port of iOS's
 * `ModerationRepository.performConsent` — validate the reviewed
 * manifest, enroll (challenge + signed payload + requestHash-bound
 * integrity token), build and sign the mandate, countersign, persist.
 *
 * Deferred with seams (see `MandateRecord.authorityRegistered`):
 * authority register-mandate, terms-currency / re-consent, and case
 * status. A record here is active once countersigned; registration
 * retry hangs off the persisted flag when that vertical lands.
 */
class ModerationRepository(
    private val backend: EnforcementBackendClient,
    private val attestation: DeviceAttestationProvider,
    private val signer: ModerationSigner,
    private val mandateStore: MandateStore,
    private val interfaceComponentId: String = INTERFACE_COMPONENT_ID,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    /** Serializes whole consent transactions WITHOUT blocking the
     * cheap readers: [mutex] guards only load/save/state, so
     * [activeMandateRecord] — on every gate check — no longer queues
     * behind a consent's enroll → countersign network round trips. */
    private val consentMutex = Mutex()
    private val state = MutableStateFlow(ModerationState())

    val snapshots: StateFlow<ModerationState> = state.asStateFlow()

    /** Load persisted records; idempotent. Call once at bootstrap. */
    suspend fun start() {
        mutex.withLock { ensureLoadedLocked() }
    }

    /** The load itself, called only while holding [mutex] — the Mutex
     * is not reentrant, so the locked paths share this instead of
     * calling [start]. */
    private suspend fun ensureLoadedLocked() {
        if (state.value.loaded) return
        state.value = ModerationState(loaded = true, records = mandateStore.load())
    }

    /**
     * The CURRENT identity's active mandate, or null — a switch to an
     * unmandated identity (or deleting the mandated one) resolves
     * `NotMandated` and routes to the consent gate, never to a
     * mismatched session the backend refuses forever. Switching back
     * finds the original record again.
     */
    suspend fun activeMandateRecord(): MandateRecord? {
        start()
        val userKey = signer.userKeyId()
        return state.value.activeMandate(userKey)
    }

    /**
     * The one-artifact consent transaction (profile §5.1). The
     * [reviewedManifest] is the exact snapshot the human reviewed —
     * its constructor is unreachable from application code, so decoded
     * fields cannot be paired with different raw bytes, and nothing is
     * refetched here.
     */
    suspend fun consent(
        listing: AuthorityListing,
        reviewedManifest: ReviewedManifest,
    ): MandateRecord = consentMutex.withLock {
        start()
        val signedManifest = reviewedManifest.signedManifest
        if (signedManifest.manifest.componentId != listing.componentId) {
            throw ModerationConsentException(
                "reviewed manifest componentId does not match the selected authority",
            )
        }
        // Validity conditions gate enrollment and signing, not just the
        // consent UI: an invalid manifest must never end up pinned by a
        // signed mandate.
        AuthorityManifestValidator.validateForConsent(signedManifest, clock())

        val userKey = signer.userKeyId()

        // Enrollment: (identity signature, integrity token) presented
        // together — bound to one payload by the signature and the
        // echoed requestHash — is the only token↔enrollment linkage.
        // An unsupported Play environment still enrolls without a
        // token against a dev backend; a production backend refuses,
        // which is the profile's answer. A throttled provider is a
        // retryable failure, not a token-less enrollment.
        val challenge = backend.fetchChallenge(purpose = "enroll")
        val challengeBytes = Base64.getDecoder().decode(challenge.challenge)
        val timestamp = Rfc3339InstantSerializer.format(clock())
        val payload = SignedSessionPayload.enrollment(challengeBytes, userKey, timestamp)
        val token = when (val answer =
            attestation.requestToken(SignedSessionPayload.requestHash(payload))) {
            is AttestationToken.Token -> answer.value
            AttestationToken.Unsupported -> null
            AttestationToken.Throttled -> throw ModerationConsentException(
                "device verification is rate-limited; try again in a minute",
            )
        }
        val signature = Base64.getEncoder().encodeToString(signer.sign(payload))
        val enrollment = try {
            backend.enrollDevice(
                EnrollmentRequest(
                    userKey = userKey,
                    timestamp = timestamp,
                    challenge = challenge.challenge,
                    integrityToken = token,
                    signature = signature,
                ),
            )
        } catch (e: BackendRejectedException) {
            // A token-less enrollment (no usable Play environment)
            // refused by a production backend is not "this device was
            // judged and refused" — it is "the rail cannot exist on
            // this hardware". Typed so consent surfaces can route it
            // to the deferrable Unavailable path (see
            // ModerationUnsupportedDeviceException) instead of the
            // retry-forever refusal loop a supported device gets.
            if (token == null) {
                throw ModerationUnsupportedDeviceException(
                    "this device has no Google Play services, and the enforcement backend " +
                        "requires them; moderation enforcement is unavailable on this hardware",
                    e,
                )
            }
            throw e
        }

        var mandate = ModerationMandate(
            user = userKey,
            interface_ = interfaceComponentId,
            authority = listing.componentId,
            manifestHash = signedManifest.manifestHash,
            classes = signedManifest.manifest.violationClasses.map { it.classId },
            deviceBinding = enrollment.deviceBinding,
            acceptedAt = Rfc3339InstantSerializer.format(clock()),
        )
        val userSignature = Base64.getEncoder().encodeToString(signer.sign(mandate.signingBytes()))
        mandate = mandate.copy(signatures = listOf(userSignature))

        // The countersignature is appended to this client's own copy;
        // the backend never hands back a mandate, so no consented
        // field can change behind the user's signature. Minimum
        // plausibility before recording: a 64-byte Ed25519 signature.
        val countersignature = backend.countersignMandate(mandate.wireBytes())
        val raw = runCatching { Base64.getDecoder().decode(countersignature.signature) }.getOrNull()
        if (raw == null || raw.size != 64) {
            throw ModerationConsentException(
                "interface countersignature is not a 64-byte Ed25519 signature",
            )
        }
        mandate = mandate.copy(signatures = listOf(userSignature, countersignature.signature))

        val record = MandateRecord(
            mandate = mandate,
            manifestBytesBase64 = Base64.getEncoder().encodeToString(signedManifest.rawBytes),
            authorityName = listing.name,
            countersigned = true,
            authorityRegistered = false,
            isActive = true,
            createdAt = Rfc3339InstantSerializer.format(clock()),
        )

        // Mandates are immutable: the previous active record is
        // deactivated, never edited beyond that flag — and only THIS
        // IDENTITY's previous record: other identities on the device
        // keep their own active mandates (activeMandate is
        // per-identity). Only this final read-modify-write needs the
        // state lock.
        mutex.withLock {
            val records = listOf(record) + state.value.records.map { existing ->
                if (existing.mandate.user == record.mandate.user) {
                    existing.copy(isActive = false)
                } else {
                    existing
                }
            }
            mandateStore.save(records)
            state.value = ModerationState(loaded = true, records = records)
        }
        record
    }

    companion object {
        /** This interface's componentId, carried in mandates and
         * matched by the backend's countersign endpoint. */
        const val INTERFACE_COMPONENT_ID: String = "onym:component:onym-android"
    }
}
