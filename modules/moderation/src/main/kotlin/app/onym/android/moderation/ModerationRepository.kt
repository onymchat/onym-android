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
 * integrity token), build and sign the mandate, countersign, persist,
 * register with the authority.
 *
 * Deferred with seams: terms-currency / re-consent and case status.
 */
class ModerationRepository(
    private val backend: EnforcementBackendClient,
    private val attestation: DeviceAttestationProvider,
    private val signer: ModerationSigner,
    private val mandateStore: MandateStore,
    private val authority: AuthorityClient,
    private val interfaceComponentId: String = INTERFACE_COMPONENT_ID,
    private val clock: () -> Instant = Instant::now,
    /** Ledger of filed reports; null leaves the report vertical dark
     * ([fileReport] refuses) without touching consent/gate callers. */
    private val reportStore: ReportFilingStore? = null,
    /** Resolves the active mandate's authority to its current
     * directory listing (the listing designates the API base URL a
     * report is delivered to). Null = not listed right now. */
    private val resolveAuthorityListing: suspend (componentId: String) -> AuthorityListing? =
        { null },
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
        var previousActive: MandateRecord? = null
        mutex.withLock {
            previousActive = state.value.records.firstOrNull {
                it.isActive && it.mandate.user == record.mandate.user
            }
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
        // Registration is delivery of the already-persisted artifact,
        // so it runs AFTER the save: a crash or network loss here
        // leaves a countersigned record whose registration retries
        // later ([registerPending]), never a signed consent that
        // evaporated.
        try {
            registerWithAuthority(record, listing)
        } catch (e: Exception) {
            // A permanent refusal of the NEW mandate must not cost the
            // identity its PREVIOUS, authority-accepted one: the
            // re-consent path deactivated it in the save above, and
            // without this rollback a stale-manifest 400 would leave
            // zero active mandates — re-consenting made things
            // strictly worse than not trying. Only the deterministic
            // pair reaches here (transients return the record).
            if (e is AuthorityRejectedException || e is AuthorityRegistrationRefusedException) {
                previousActive?.let { previous ->
                    updateRecord(previous) { it.copy(isActive = true) }
                }
            }
            throw e
        }
    }

    /**
     * Everything a Settings surface shows for the CURRENT identity,
     * resolved in one suspend read so no caller re-derives "active"
     * or "previous" from the raw records and silently drops the
     * per-identity rule ([ModerationState.activeMandate]'s whole
     * point): [active] and [previous] are THIS identity's records
     * only, and [registrationPending] is meaningful only when
     * [active] is non-null.
     */
    data class IdentityConsentState(
        val active: MandateRecord?,
        val previous: List<MandateRecord>,
        val registrationPending: Boolean,
    )

    /** See [IdentityConsentState]. Throws when the identity key
     * cannot be read — callers must treat that as UNRESOLVED, never
     * as "no mandate" (a screen that answers "choose an authority"
     * to a mandated user invites a duplicate consent). */
    suspend fun identityConsentState(): IdentityConsentState {
        start()
        val userKey = signer.userKeyId()
        val records = state.value.records.filter { it.mandate.user == userKey }
        val active = records.firstOrNull { it.isActive }
        return IdentityConsentState(
            active = active,
            previous = records.filter { !it.isActive },
            registrationPending = active != null &&
                active.countersigned && !active.authorityRegistered,
        )
    }

    /**
     * The current identity's active record still awaiting authority
     * registration, or null. The retry hook: a caller holding a
     * directory listing for its authority completes delivery with
     * [registerPending].
     */
    suspend fun pendingRegistration(): MandateRecord? =
        activeMandateRecord()?.takeIf { it.countersigned && !it.authorityRegistered }

    /**
     * Complete delivery of a persisted countersigned mandate to its
     * authority. The user already signed these exact bytes — this
     * creates no consent, it only retries the registration a previous
     * session could not finish. No-op when nothing is pending or when
     * [listing] designates a different authority than the pending
     * mandate names.
     */
    suspend fun registerPending(listing: AuthorityListing) = consentMutex.withLock {
        val pending = pendingRegistration() ?: return@withLock
        if (pending.mandate.authority != listing.componentId) return@withLock
        registerWithAuthority(pending, listing)
    }

    /**
     * Register [record]'s mandate with the authority and return the
     * record as persisted afterwards.
     *
     * Failure policy (mirroring iOS `registerPending`):
     *  - transport failure / 5xx / 408 / 429 — the authority may have
     *    committed or may accept an exact replay, so the artifact is
     *    KEPT (`authorityRegistered = false`) and the consent still
     *    stands; a later [registerPending] completes delivery. The
     *    authority accepts exact replays idempotently.
     *  - deterministic 4xx, a receipt naming a different mandateRef,
     *    or `accepted: false` — exact replay can never turn this into
     *    an acceptance (e.g. the authority rotated its manifest after
     *    the review snapshot), so the record is DEACTIVATED — leaving
     *    it active would pin the identity to a mandate its authority
     *    never accepted, unfixable by retry — and the failure thrown
     *    for the consent surface to present. A fresh consent against
     *    current terms is the only repair.
     */
    private suspend fun registerWithAuthority(
        record: MandateRecord,
        listing: AuthorityListing,
    ): MandateRecord {
        val receipt = try {
            authority.registerMandate(listing, record.mandate)
        } catch (_: AuthorityUnreachableException) {
            return record
        } catch (e: AuthorityRejectedException) {
            if (!e.isDeterministic) return record
            deactivate(record, refusal = e.message)
            throw e
        }

        // Receipt refusals are AuthorityRegistrationRefusedException,
        // not ModerationConsentException: consent surfaces classify the
        // type as deterministic, and a permanently refused registration
        // routed through the generic path would escalate to the
        // deferrable Unavailable state — a refused caller handed the
        // bypass the deterministic split exists to close.
        val expectedRef = record.mandate.mandateHash()
        if (receipt.mandateRef != expectedRef) {
            val refusal = "authority acknowledged a different mandate reference " +
                "(expected $expectedRef, received ${receipt.mandateRef})"
            deactivate(record, refusal)
            throw AuthorityRegistrationRefusedException(refusal)
        }
        if (!receipt.accepted) {
            val refusal = "authority did not accept the mandate"
            deactivate(record, refusal)
            throw AuthorityRegistrationRefusedException(refusal)
        }

        return updateRecord(record) { it.copy(authorityRegistered = true) }
    }

    /**
     * The reason the current identity's registration was permanently
     * refused, or null — non-null only while the identity holds NO
     * active mandate, so a consent surface can say WHY consent needs
     * redoing (the boot-time retry swallows its exceptions; this is
     * how the reason survives to the next screen). Goes quiet by
     * construction once a fresh consent activates a record.
     */
    suspend fun registrationRefusal(): String? {
        start()
        val userKey = signer.userKeyId()
        if (state.value.activeMandate(userKey) != null) return null
        return state.value.records
            .firstOrNull { it.mandate.user == userKey && it.registrationRefusal != null }
            ?.registrationRefusal
    }

    private suspend fun deactivate(record: MandateRecord, refusal: String?) {
        updateRecord(record) { it.copy(isActive = false, registrationRefusal = refusal) }
    }

    /** Replace the exact persisted [record] (matched by its finalized
     * mandate and creation instant) with [transform] of it. Failing
     * loudly on a miss is deliberate: every caller has just read or
     * written the record under a lock, so a miss is a logic error —
     * silently reporting an un-persisted registration or deactivation
     * as done would be worse than the crash. */
    private suspend fun updateRecord(
        record: MandateRecord,
        transform: (MandateRecord) -> MandateRecord,
    ): MandateRecord = mutex.withLock {
        ensureLoadedLocked()
        var updated: MandateRecord? = null
        val records = state.value.records.map { existing ->
            if (existing.mandate == record.mandate && existing.createdAt == record.createdAt) {
                transform(existing).also { updated = it }
            } else {
                existing
            }
        }
        val result = checkNotNull(updated) {
            "no persisted record matches the one being updated (user ${record.mandate.user})"
        }
        mandateStore.save(records)
        state.value = ModerationState(loaded = true, records = records)
        result
    }

    // ─── report filing ───────────────────────────────────────────────

    /** Serializes filings so two taps on Submit can't mint two signed
     * artifacts for one message. */
    private val reportMutex = Mutex()

    /**
     * The classes the current identity can report under: the consented
     * manifest's published classes intersected with the mandate's own
     * class list. Decoded from the retained exact bytes the mandate
     * pinned — never a refetch, so the picker can only offer terms the
     * user actually consented to.
     */
    suspend fun availableReportClasses(): List<ViolationClass> {
        val record = activeReportingMandate() ?: throw ReportFilingException.ReportingUnavailable()
        val manifest = consentedManifest(record) ?: throw ReportFilingException.ReportingUnavailable()
        return manifest.violationClasses.filter { it.classId in record.mandate.classes }
    }

    /**
     * File a report with the mandate's authority (Moderation.md §5.4).
     *
     * Ordering is load-bearing:
     *  1. Verify the evidence locally (the accused's signature over
     *     the disclosed bytes) — never deliver what can only be
     *     refused.
     *  2. Persist the signed artifact BEFORE any network call, so an
     *     ambiguous failure replays the exact bytes on the next
     *     Submit (a filed report is immutable; only byte-identical
     *     retries are accepted).
     *  3. Upload image blobs, then the report that names them — a
     *     report naming an un-uploaded digest is refused.
     *
     * Filing the same message + class again returns the stored
     * receipt (or [ReportFilingException.AlreadyFiled]) without a
     * second network delivery.
     */
    suspend fun fileReport(
        evidence: ReportableEvidence,
        classId: String,
    ): ReportReceipt = reportMutex.withLock {
        val reports = reportStore ?: throw ReportFilingException.ReportingUnavailable()
        val record = activeReportingMandate() ?: throw ReportFilingException.ReportingUnavailable()
        val reporter = signer.userKeyId()
        if (reporter != record.mandate.user) throw ReportFilingException.ReportingUnavailable()
        val manifest = consentedManifest(record) ?: throw ReportFilingException.ReportingUnavailable()
        if (classId !in record.mandate.classes ||
            manifest.violationClasses.none { it.classId == classId }
        ) {
            throw ReportFilingException.ClassOutsideMandate()
        }
        verifyAuthenticity(evidence)

        val listing = resolveAuthorityListing(record.mandate.authority)
            ?: throw ReportFilingException.AuthorityUnavailable()
        val mandateRef = record.mandate.mandateHash()

        // Idempotency: one signed artifact per (message, reporter,
        // mandate, accused, class, evidence). A hit replays or
        // short-circuits; it never re-signs.
        val existing = reports.load().firstOrNull {
            it.sourceMessageId == evidence.sourceMessageId &&
                it.report.reporter == reporter &&
                it.report.reporterMandate == mandateRef &&
                it.report.accused == evidence.accused &&
                it.report.classId == classId &&
                it.report.evidence.map(ReportEvidenceItem::disclosedContent) ==
                listOf(evidence.disclosedContent)
        }
        if (existing != null) {
            existing.receipt?.let { return@withLock it }
            if (existing.resolvedWithoutReceipt) {
                throw ReportFilingException.AlreadyFiled(existing.report.reportId)
            }
            return@withLock submitReport(existing, listing, evidence.images)
        }

        val report = ModerationReport(
            reportId = "report-" + java.util.UUID.randomUUID().toString().lowercase(),
            reporter = reporter,
            reporterMandate = mandateRef,
            accused = evidence.accused,
            classId = classId,
            evidence = listOf(
                ReportEvidenceItem(
                    disclosedContent = evidence.disclosedContent,
                    authenticityProof = evidence.authenticityProofBase64,
                ),
            ),
            filedAt = Rfc3339InstantSerializer.format(clock()),
        )
        val signed = report.copy(
            signature = Base64.getEncoder().encodeToString(signer.sign(report.signingBytes())),
        )
        val filing = ReportFilingRecord(
            sourceMessageId = evidence.sourceMessageId,
            report = signed,
            authorityName = record.authorityName,
        )
        // Persist before delivering: an artifact that can't be
        // persisted is never sent (a crash after delivery would lose
        // the only copy of what was disclosed).
        saveReports(listOf(filing) + reports.load())
        submitReport(filing, listing, evidence.images)
    }

    /** Deliver blobs-first, then the report; classify the outcome the
     * way the artifact's lifecycle demands. */
    private suspend fun submitReport(
        filing: ReportFilingRecord,
        listing: AuthorityListing,
        images: List<ReportableImage>,
    ): ReportReceipt {
        val reports = checkNotNull(reportStore)
        for (image in images) {
            try {
                val timestamp = Rfc3339InstantSerializer.format(clock())
                val credential = "evidence-blob:${image.sha256}:$timestamp"
                    .toByteArray(Charsets.UTF_8)
                authority.uploadEvidenceImage(
                    listing = listing,
                    sha256 = image.sha256,
                    bytes = image.bytes,
                    userKey = signer.userKeyId(),
                    timestamp = timestamp,
                    signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(credential)),
                )
            } catch (e: AuthorityRejectedException) {
                // 409 = the bytes are already on file: agreement, the
                // filing proceeds. Everything else fails the filing —
                // deterministic upload refusals discard the artifact
                // (its media can never be accepted), transient ones
                // keep it for replay.
                if (e.statusCode == 409) continue
                if (e.isDeterministic) discardReport(filing)
                throw e
            }
        }
        val receipt = try {
            authority.fileReport(listing, filing.report.wireBytes())
        } catch (e: AuthorityRejectedException) {
            if (e.statusCode == 409) {
                // This (reporter, reportId) is already on a case —
                // terminal success without a receipt of our own.
                updateReport(filing) { it.copy(resolvedWithoutReceipt = true) }
                throw ReportFilingException.AlreadyFiled(filing.report.reportId)
            }
            if (e.isDeterministic) discardReport(filing)
            throw e
        }
        if (receipt.reportId != filing.report.reportId) {
            // Acknowledged something else — keep the artifact and
            // classify retryable, like an unparseable 2xx.
            throw AuthorityUnreachableException(
                "authority acknowledged reportId ${receipt.reportId} for " +
                    filing.report.reportId,
            )
        }
        updateReport(filing) { it.copy(receipt = receipt) }
        return receipt
    }

    /** Active + countersigned + registered — the only mandate a report
     * may cite: an unregistered one has no standing at the authority. */
    private suspend fun activeReportingMandate(): MandateRecord? =
        activeMandateRecord()?.takeIf { it.countersigned && it.authorityRegistered }

    private fun consentedManifest(record: MandateRecord): AuthorityManifest? = runCatching {
        ModerationJson.json.decodeFromString(
            AuthorityManifest.serializer(),
            Base64.getDecoder().decode(record.manifestBytesBase64).decodeToString(),
        )
    }.getOrNull()

    /** The same check the authority runs, run first: the accused's key
     * must verify the proof over the disclosed bytes. Refusing locally
     * keeps garbage evidence off the wire entirely. */
    private fun verifyAuthenticity(evidence: ReportableEvidence) {
        val accusedKey = keyBytesFromReference(evidence.accused)?.takeIf { it.size == 32 }
            ?: throw ReportFilingException.AuthenticityUnverified()
        val signature = runCatching {
            Base64.getDecoder().decode(evidence.authenticityProofBase64)
        }.getOrNull()?.takeIf { it.size == 64 }
            ?: throw ReportFilingException.AuthenticityUnverified()
        if (evidence.disclosedContent.isEmpty()) {
            throw ReportFilingException.AuthenticityUnverified()
        }
        val message = evidence.disclosedContent.toByteArray(Charsets.UTF_8)
        val verifier = org.bouncycastle.crypto.signers.Ed25519Signer().apply {
            init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(accusedKey, 0))
            update(message, 0, message.size)
        }
        if (!verifier.verifySignature(signature)) {
            throw ReportFilingException.AuthenticityUnverified()
        }
    }

    private suspend fun discardReport(filing: ReportFilingRecord) {
        val reports = checkNotNull(reportStore)
        saveReports(reports.load().filterNot { it.report.reportId == filing.report.reportId })
    }

    private suspend fun updateReport(
        filing: ReportFilingRecord,
        transform: (ReportFilingRecord) -> ReportFilingRecord,
    ) {
        val reports = checkNotNull(reportStore)
        saveReports(
            reports.load().map {
                if (it.report.reportId == filing.report.reportId) transform(it) else it
            },
        )
    }

    /** Bound the ledger: resolved rows beyond the cap fall off oldest
     * first; unresolved rows (signed but undelivered artifacts) are
     * always kept — dropping one would orphan a delivery still owed. */
    private suspend fun saveReports(records: List<ReportFilingRecord>) {
        val reports = checkNotNull(reportStore)
        var resolvedSeen = 0
        val bounded = records.filter { record ->
            if (!record.isResolved) true else (resolvedSeen++ < MAX_RESOLVED_REPORT_RECORDS)
        }
        reports.save(bounded)
    }

    companion object {
        /** This interface's componentId, carried in mandates and
         * matched by the backend's countersign endpoint. */
        const val INTERFACE_COMPONENT_ID: String = "onym:component:onym-android"

        /** Resolved-ledger bound, oldest-first pruning (iOS parity). */
        const val MAX_RESOLVED_REPORT_RECORDS: Int = 128
    }
}

/**
 * Recipient-held evidence ready to disclose: the exact proof preimage
 * (`disclosedContent`), the sender's signature over it, and — for a
 * photo message — the decrypted image bytes carried here rather than
 * re-fetched at submit time, so exactly what the user agreed to
 * disclose is what uploads.
 */
data class ReportableEvidence(
    /** Lowercase UUID of the reported chat message. */
    val sourceMessageId: String,
    /** `onym:key:<hex>` — the accused sender. */
    val accused: String,
    val disclosedContent: String,
    val authenticityProofBase64: String,
    val images: List<ReportableImage> = emptyList(),
)

/** A disclosed photo: [sha256] is the PLAINTEXT digest the sender
 * committed to — both the upload's address and its authenticity
 * binding. */
class ReportableImage(
    val sha256: String,
    val bytes: ByteArray,
)

/**
 * Failure modes of [ModerationRepository.fileReport], sealed so the
 * report UI can `when`-exhaust into precise copy.
 */
sealed class ReportFilingException(message: String) : Exception(message) {
    /** No active, countersigned, authority-registered mandate (or the
     * report vertical isn't wired). */
    class ReportingUnavailable :
        ReportFilingException("reporting requires an active registered mandate")

    /** The class isn't in both the mandate and the consented manifest. */
    class ClassOutsideMandate :
        ReportFilingException("class is outside the consented mandate")

    /** The evidence does not verify against the accused's key. */
    class AuthenticityUnverified :
        ReportFilingException("evidence does not verify against the accused's key")

    /** The mandate's authority has no current directory listing. */
    class AuthorityUnavailable :
        ReportFilingException("the authority is not in the directory right now")

    /** The authority already holds this filing on a case — success in
     * every way that matters to the reporter. */
    class AlreadyFiled(val reportId: String) :
        ReportFilingException("report $reportId is already on file")
}
