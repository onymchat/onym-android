package app.onym.android.moderation

import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The declared cadence from the device-recall profile §5.2: gate
 * check at launch and at least once per [interval]; a device that
 * cannot reach the backend operates on its last known state for
 * [offlineGrace], then degrades to gate-check-required. */
data class GateCheckPolicy(
    /** Default `P1D`. */
    val interval: Duration = Duration.ofDays(1),
    /** Default `P3D`. */
    val offlineGrace: Duration = Duration.ofDays(3),
    /**
     * Serial (non-forced) checks completing within this window skip
     * the network: coalescing only shares *concurrent* flights, so
     * back-to-back foreground cycles each spent a challenge and a
     * Play Integrity standard request against quota (profile §5.2's
     * one-scheduler requirement). Forced checks (retry,
     * post-consent) always run.
     */
    val minRecheckInterval: Duration = Duration.ofMinutes(1),
) {
    companion object {
        val DEFAULT = GateCheckPolicy()
    }
}

/** What the root of the app switches on. Only [Banned] and
 * [GateCheckRequired] block it. */
sealed interface GateStatus {
    /**
     * No check has landed yet this process. Distinct from
     * [NotMandated] deliberately: on a cold start for an
     * already-mandated user, mapping the pre-first-check state to
     * "no mandate" rendered the full-screen consent surface — with a
     * live Agree that would mint a second enrollment — for the
     * window between the (fast) directory probe and the (slow)
     * challenge → Play Integrity → gate-check round trip. Unknown
     * renders the app and decides nothing.
     */
    data object Unknown : GateStatus

    /**
     * No active mandate yet — the consent gate applies, not this one.
     * NOT an operational state and never an escape hatch: losing the
     * mandate routes to re-consent, after which a fresh gate check
     * re-reads the device marks.
     */
    data object NotMandated : GateStatus

    /** Operating. Non-empty [openCases] renders the non-blocking case
     * banner — the case-open mark must not degrade service. */
    data class Operational(val openCases: List<CaseNotice> = emptyList()) : GateStatus

    /** `bitSecond`: the app refuses to operate and shows the ban UX. */
    data class Banned(val state: BanState) : GateStatus

    /** Blocking: no trustworthy gate answer (and grace exhausted). */
    data class GateCheckRequired(val reason: CheckRequiredReason) : GateStatus
}

/**
 * Owns the gate-check cadence: launch + interval checks, offline grace
 * on the persisted last-known state, degradation toward blocking —
 * never toward unmoderated operation. All state transitions go through
 * the pure [derive] so the arithmetic is testable without coroutine
 * plumbing. The literal Android port of iOS's `GateCheckRepository`,
 * plus one platform-specific attempt kind: a [AttemptOutcome.Throttled]
 * provider (Play's prepare cap / `TOO_MANY_REQUESTS`) behaves like
 * unreachability — served from grace — but degrades to
 * `attestationUnavailable` rather than `offlineGraceExpired`.
 */
class GateCheckRepository(
    private val attestation: DeviceAttestationProvider,
    private val backend: EnforcementBackendClient,
    private val moderation: ModerationRepository,
    private val signer: ModerationSigner,
    private val store: GateStateStore,
    private val scope: CoroutineScope,
    private val policy: GateCheckPolicy = GateCheckPolicy.DEFAULT,
    private val clock: () -> Instant = Instant::now,
) {
    /** Outcome of one contact attempt with the backend. */
    sealed interface AttemptOutcome {
        data class Success(val result: GateCheckResult) : AttemptOutcome

        /** Network failure / backend unreachable — grace arithmetic
         * keeps serving the last known state within the window. */
        data object Unreachable : AttemptOutcome

        /** The attestation provider is rate-limited; no request was
         * sent. Grace applies; past it, the honest reason is that
         * attestation (not the network) is unavailable. */
        data object Throttled : AttemptOutcome

        /**
         * The backend answered and deterministically refused the
         * session. NOT unreachable: a cached `clear` must not keep
         * serving for the grace window on the say-so of a refusal.
         * `ENROLLMENT_LOST` (the backend's `no_mandate`) routes to
         * re-consent; `BACKEND_REFUSED` to retry.
         */
        data class Refused(val reason: CheckRequiredReason) : AttemptOutcome
    }

    private val mutex = Mutex()
    private val state = MutableStateFlow<GateStatus>(GateStatus.Unknown)
    private var loopJob: Job? = null

    /**
     * The check currently on the wire, if any — real in-flight
     * coalescing, not just stale-result discarding. Launch, the
     * interval loop, foreground, and retry all funnel through
     * [checkNow]; without sharing the flight, a cold start fires two
     * concurrent checks (loop + `onStart`) and foreground thrash
     * multiplies Play Integrity standard requests 1:1 against quota
     * (profile §5.2's "one scheduler" requirement). Guarded by
     * [mutex].
     */
    private var inFlight: Job? = null

    /** Monotonic tag for check runs; a completion whose generation is
     * no longer the newest is discarded — a retry can legitimately
     * start after a slow run finished its network hop, and the slow
     * `clear` must not land after a fast `banned` and reopen the
     * app. */
    private var generation: Long = 0L

    /**
     * Session signatures are single-use server-side and the signed
     * payload is second-precision; two checks in the same second with
     * identical fields would self-inflict a replay refusal. Bumping
     * into the next second stays inside the server's freshness window.
     * Guarded by [timestampMutex]: the bump is a read-modify-write,
     * and even with coalesced checks a retry can overlap a finishing
     * run. (The challenge already varies per session, so collisions
     * need both a same-second call and a reused challenge — this keeps
     * the iOS behavior anyway, cheaply.)
     */
    private var lastSessionTimestamp: Instant = Instant.EPOCH
    private val timestampMutex = Mutex()

    val snapshots: StateFlow<GateStatus> = state.asStateFlow()

    /** Launch check + interval loop. Idempotent. Deadlines are
     * wall-clock-anchored on the previous check so check duration does
     * not accumulate drift; a deadline already past (the app was in
     * the background across it) checks immediately and re-anchors. */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            var due = clock()
            while (true) {
                checkNow()
                due = due.plus(policy.interval)
                val delayMillis = Duration.between(clock(), due).toMillis()
                if (delayMillis > 0) {
                    delay(delayMillis)
                } else {
                    due = clock()
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Run one gate check now (launch, foreground, retry button).
     * Coalescing: a call arriving while a check is on the wire joins
     * that flight instead of spending a second challenge and Play
     * Integrity request — the answer is at most one flight old either
     * way.
     *
     * [force] is for callers whose *state changed* — post-consent
     * ([ModerationRepository.consent] just landed a new mandate). A
     * flight already on the wire read `activeMandateRecord()` before
     * the change, so joining it would adopt a pre-consent answer
     * (`no_mandate` → back to the consent surface the user just
     * completed). Forcing starts a fresh run; the generation guard
     * discards the stale flight's result whenever it lands.
     */
    suspend fun checkNow(force: Boolean = false) {
        val flight = mutex.withLock {
            val active = inFlight?.takeIf { it.isActive }
            val recentEnough = lastRunCompletedAt?.let { completed ->
                Duration.between(completed, clock()) < policy.minRecheckInterval
            } == true
            when {
                active != null && !force -> active
                // A run just completed: the current state is at most
                // minRecheckInterval old, and serial foreground thrash
                // must not spend quota re-deriving it.
                recentEnough && !force -> null
                else -> scope.launch { runCheck() }.also { inFlight = it }
            }
        }
        flight?.join()
    }

    /** When the last run finished, whatever its outcome — the
     * min-interval guard's memory. Guarded by [mutex]. */
    private var lastRunCompletedAt: Instant? = null

    private suspend fun runCheck() {
        val taken = mutex.withLock { ++generation }
        try {
            val record = moderation.activeMandateRecord()
            if (record == null) {
                // No mandate on file — but the PERSISTED gate state
                // may still carry a ban or a sticky refusal, and a
                // cleared/corrupt mandate store must not walk around
                // the laundering wall: Settings -> Clear data ->
                // offline used to land Operational through the
                // consent softening. Platform-state-first: a cached
                // ban keeps blocking, a sticky refusal keeps
                // blocking; only a genuinely clean history answers
                // NotMandated (-> the consent gate).
                val persisted = store.load()
                val lastResult = persisted?.lastResult
                val fallback = when {
                    persisted?.refusalReason != null ->
                        GateStatus.GateCheckRequired(persisted.refusalReason)
                    lastResult is GateCheckResult.Banned -> GateStatus.Banned(lastResult.ban)
                    else -> GateStatus.NotMandated
                }
                mutex.withLock {
                    if (taken == generation) state.value = fallback
                }
                return
            }

            val attempt = performAttempt(record)

            mutex.withLock {
                if (taken != generation) return
                val (status, persisted) = derive(store.load(), attempt, clock(), policy)
                store.save(persisted)
                state.value = status
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The paths outside performAttempt's own try — the mandate
            // and gate-state stores. An escaped exception here is an
            // uncaught crash in a SupervisorJob child; a swallowed one
            // that leaves the last state standing is unmoderated
            // operation on a store fault. Degrade toward blocking.
            // (No logger: this module is log-free like the repo's
            // other library modules; the blocked gate IS the signal.)
            mutex.withLock {
                if (taken == generation) {
                    state.value =
                        GateStatus.GateCheckRequired(CheckRequiredReason.NEVER_CHECKED)
                }
            }
        } finally {
            mutex.withLock { lastRunCompletedAt = clock() }
        }
    }

    private suspend fun performAttempt(record: MandateRecord): AttemptOutcome {
        return try {
            val challenge = backend.fetchChallenge(purpose = "gate")
            val challengeBytes = Base64.getDecoder().decode(challenge.challenge)
            val timestamp = timestampMutex.withLock {
                val now = clock()
                val bumped = lastSessionTimestamp.plusSeconds(1)
                val chosen = if (now.isAfter(bumped)) now else bumped
                lastSessionTimestamp = chosen
                Rfc3339InstantSerializer.format(chosen)
            }
            val mandateRef = record.mandate.mandateHash()
            val payload = SignedSessionPayload.gateCheck(
                challenge = challengeBytes,
                userKey = record.mandate.user,
                mandateRef = mandateRef,
                timestamp = timestamp,
            )
            val token = when (
                val answer = attestation.requestToken(SignedSessionPayload.requestHash(payload))
            ) {
                is AttestationToken.Token -> answer.value
                // No usable Play environment: send without a token and
                // let the backend answer (checkRequired) — degradation
                // toward blocking, never unmoderated operation.
                AttestationToken.Unsupported -> null
                // Rate-limited: nothing to send that the backend could
                // trust; serve grace and try at the next slot.
                AttestationToken.Throttled -> return AttemptOutcome.Throttled
            }
            val signature = Base64.getEncoder().encodeToString(signer.sign(payload))
            val result = backend.gateCheck(
                GateCheckRequest(
                    userKey = record.mandate.user,
                    mandateRef = mandateRef,
                    timestamp = timestamp,
                    challenge = challenge.challenge,
                    integrityToken = token,
                    signature = signature,
                ),
            )
            // `enrollmentLost` is client-derived from the backend's
            // `no_mandate` envelope; a conforming backend never puts it
            // in a 200 body. If one does anyway, normalize it onto the
            // refusal path so downstream sees exactly one route.
            if (result is GateCheckResult.CheckRequired &&
                result.reason == CheckRequiredReason.ENROLLMENT_LOST
            ) {
                AttemptOutcome.Refused(CheckRequiredReason.ENROLLMENT_LOST)
            } else {
                AttemptOutcome.Success(result)
            }
        } catch (e: BackendRejectedException) {
            // Only a recognizable SESSION refusal blocks. A broader
            // any-4xx rule would convert a deploy or encoding
            // regression (404 on a renamed route, 400 from body drift
            // — including a spent challenge) into an immediate hard
            // block with grace discarded; those fall to Unreachable
            // and the grace window.
            sessionRefusalReason(e)?.let { AttemptOutcome.Refused(it) } ?: AttemptOutcome.Unreachable
        } catch (_: Exception) {
            AttemptOutcome.Unreachable
        }
    }

    companion object {
        /**
         * `no_mandate` is its own state because its recovery is
         * different in kind: no amount of retrying fixes a backend
         * that has lost the enrollment — only re-consent does.
         */
        fun sessionRefusalReason(e: BackendRejectedException): CheckRequiredReason? = when {
            e.rawCode == "no_mandate" -> CheckRequiredReason.ENROLLMENT_LOST
            e.statusCode == 401 || e.statusCode == 403 || e.rawCode == "signature_invalid" ->
                CheckRequiredReason.BACKEND_REFUSED
            else -> null
        }

        /**
         * The profile-§5.2 rules as one pure function:
         * - success → status from the result; persist `{result, now}`;
         * - refused → block now, persisted state kept (a later success
         *   overwrites it);
         * - unreachable/throttled within [GateCheckPolicy.offlineGrace]
         *   of the last success → keep serving the last known state;
         * - past grace → `offlineGraceExpired` (unreachable) or
         *   `attestationUnavailable` (throttled);
         * - no history → `neverChecked` / `attestationUnavailable`;
         * - clock behind `lastSuccessAt` → `clockRollback` (a negative
         *   age would satisfy the grace comparison forever).
         * Degradation only ever moves toward blocking.
         */
        fun derive(
            persisted: PersistedGateState?,
            attempt: AttemptOutcome,
            now: Instant,
            policy: GateCheckPolicy,
        ): Pair<GateStatus, PersistedGateState?> = when (attempt) {
            // Success clears any sticky refusal — the backend that
            // refused has now answered.
            is AttemptOutcome.Success -> statusFor(attempt.result) to PersistedGateState(
                lastResult = attempt.result,
                lastSuccessAt = Rfc3339InstantSerializer.format(now),
            )
            // The refusal is persisted (sticky, cleared only by a
            // later success), so a force-stop + offline relaunch
            // cannot launder it into the grace window's territory.
            // With no history there is nothing to stamp it on — and
            // nothing cached for a relaunch to serve either, so the
            // relaunch blocks (neverChecked) regardless.
            is AttemptOutcome.Refused ->
                GateStatus.GateCheckRequired(attempt.reason) to persisted?.copy(
                    refusalReason = attempt.reason,
                    refusedAt = Rfc3339InstantSerializer.format(now),
                )
            AttemptOutcome.Unreachable ->
                graceOrBlock(persisted, now, policy, CheckRequiredReason.OFFLINE_GRACE_EXPIRED)
            AttemptOutcome.Throttled ->
                graceOrBlock(persisted, now, policy, CheckRequiredReason.ATTESTATION_UNAVAILABLE)
        }

        private fun graceOrBlock(
            persisted: PersistedGateState?,
            now: Instant,
            policy: GateCheckPolicy,
            expiredReason: CheckRequiredReason,
        ): Pair<GateStatus, PersistedGateState?> {
            // A persisted refusal outranks the cached success it rode
            // in on: grace exists for network conditions, and a
            // refusal is a reachable backend's answer.
            persisted?.refusalReason?.let { reason ->
                return GateStatus.GateCheckRequired(reason) to persisted
            }
            if (persisted == null) {
                val reason = if (expiredReason == CheckRequiredReason.ATTESTATION_UNAVAILABLE) {
                    CheckRequiredReason.ATTESTATION_UNAVAILABLE
                } else {
                    CheckRequiredReason.NEVER_CHECKED
                }
                return GateStatus.GateCheckRequired(reason) to null
            }
            val lastSuccess = runCatching {
                Rfc3339InstantSerializer.parse(persisted.lastSuccessAt)
            }.getOrNull()
                ?: return GateStatus.GateCheckRequired(CheckRequiredReason.NEVER_CHECKED) to null
            val age = Duration.between(lastSuccess, now)
            if (age.isNegative) {
                return GateStatus.GateCheckRequired(CheckRequiredReason.CLOCK_ROLLBACK) to persisted
            }
            if (age <= policy.offlineGrace) {
                return statusFor(persisted.lastResult) to persisted
            }
            return GateStatus.GateCheckRequired(expiredReason) to persisted
        }

        fun statusFor(result: GateCheckResult): GateStatus = when (result) {
            is GateCheckResult.Clear -> GateStatus.Operational()
            is GateCheckResult.CaseOpen -> GateStatus.Operational(result.notices)
            is GateCheckResult.Banned -> GateStatus.Banned(result.ban)
            is GateCheckResult.CheckRequired -> GateStatus.GateCheckRequired(result.reason)
        }
    }
}
