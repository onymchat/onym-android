package app.onym.android.push

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the last reconciliation pass concluded — the interactor's one
 * observable surface beyond [PushPreferenceProvider.registeredFlow],
 * added so a terminal failure is DIAGNOSABLE instead of rendering as
 * "still activating" forever. Privacy: carries the backend's error
 * CODE at most — never request contents, tokens, or messages (the
 * no-activity-logging stance holds; this is state, not a log).
 */
sealed interface PushRegistrationState {
    /** No pass has run since construction, or push is off. */
    data object Idle : PushRegistrationState

    /** The last pass converged without a registration to show for it
     * — waiting on inputs (token/identities) or on the next pass. */
    data object Activating : PushRegistrationState

    /** The backend confirmed the current registration. */
    data object Registered : PushRegistrationState

    /**
     * The last pass failed. [willRetry] says whether a self-wake is
     * outstanding ("couldn't activate — will keep trying") or the
     * deterministic attempt bound was reached ("couldn't activate —
     * check configuration"; only a state-changing trigger retries).
     * [code] is the backend's error vocabulary when the failure was a
     * refusal, null for transport-level failures.
     */
    data class Failed(
        val code: PushBackendErrorCode?,
        val willRetry: Boolean,
    ) : PushRegistrationState
}

/**
 * The reconciler: converges what the push backend holds toward what
 * this device currently wants — one registration covering ALL
 * identities' inbox tags with their relay URLs and the current FCM
 * token, or nothing at all. Mirrors the reviewed iOS
 * `PushRegistrationInteractor` behavior precisely.
 *
 * Inputs arrive as events ([updateToken], [updateSubscriptions],
 * [pushEnabled], [pushDisabled]); each schedules a debounced pass on
 * [scope]. Passes are strictly serialized — one worker loop, so no
 * concurrent passes and no double-spent challenge/signature — and
 * triggers landing mid-pass coalesce into exactly one follow-up.
 *
 * The registration is **replace-all**: every register sends the full
 * subscription set, so the backend never accumulates state a later
 * pass didn't assert. An EMPTY list is meaningful (no identities →
 * watch nothing) and is sent; only `null` (identities not yet loaded)
 * blocks a pass, so a cold start can never register an empty set it
 * didn't mean.
 *
 * Skip arithmetic: a pass sends nothing when the fingerprint
 * (`sha256(sha256(tokenBytes) || subscriptionsDigest)` hex) is
 * unchanged AND `now < lastRegisteredAt + refreshInterval` AND
 * `now < expiresAt − margin`, where `margin = min(7d, window/2)` and
 * window is the server-granted registration lifetime — so short
 * server windows still refresh at their midpoint.
 *
 * The arithmetic above only runs when a trigger arrives — this class
 * GUARANTEES no cadence of its own. Keeping the registration from
 * lapsing on a quiet app is the app-integration layer's job, and it
 * does it: `PushCoordinator.start()` runs at every process launch
 * (Application onCreate) and, when the preference is on, calls
 * [pushEnabled] — so every app start is a pass, and the margin
 * arithmetic gets its chance whenever the user actually uses the
 * device the wakes are for. A device whose app never opens within the
 * server window lets the registration lapse server-side by design:
 * the backend must not watch relays forever for a device that
 * stopped showing up.
 *
 * Durability contract (see [PushPreferenceProvider]): a token the
 * backend must forget — disable, or the OLD token on rotation — is
 * written to `pendingUnregisterToken` BEFORE the unregister attempt
 * and cleared on success. Refusal CLASSIFICATION never expires the
 * debt: the backend's unregister is idempotent (an unknown token
 * still answers 200), so a refusal can never mean "not registered
 * here" — it means this client is broken (clock skew, key mismatch,
 * wire drift), and erasing the debt on it would erase the only
 * record that could ever retry the forget while the backend keeps
 * watching a device that asked to stop. Instead the debt is bounded
 * by ATTEMPT COUNT and AGE together: it expires only after
 * [DEBT_ATTEMPT_LIMIT] failed attempts AND [DEBT_MAX_AGE] since the
 * first — both, so a slowly-retrying device is not prematurely
 * dropped — with the bookkeeping persisted alongside the token.
 * Every still-owed debt is drained before any register. Disable
 * therefore works from `lastRegisteredToken` even when FCM no longer
 * answers, an offline disable is retried until the server confirms,
 * and a disable that lands while a register is suspended on the
 * network converts the just-registered token into pending debt
 * instead of recording it.
 *
 * Failures are quiet — no user-activity logging (privacy: the
 * backend registration IS activity metadata) — and leave the durable
 * state untouched. RETRY CADENCE CONTRACT: this class is not the
 * periodic driver. The app-integration layer re-runs a pass at every
 * app start and foreground (`PushCoordinator.start()` /
 * `checkRevocation()`), and ordinary triggers (token rotation,
 * subscription change, toggle) arrive on their own; what this class
 * adds is exactly one delayed self-wake per failed pass, with
 * exponential backoff capped at [failureRetryCap] — so an offline
 * disable keeps retrying while the process lives instead of leaving
 * the backend watching until an unrelated trigger happens to fire.
 * Classification (see [PushBackendRejectedException.deterministic])
 * shapes the PACING, never whether state survives: a DETERMINISTIC
 * refusal earns the same bounded backoff but stops self-waking after
 * [DETERMINISTIC_RETRY_LIMIT] consecutive failures — a 4xx is often
 * really transient (a challenge outlived by a slow network, a
 * transiently-bad relay set), so suppressing the self-wake outright
 * left the pass permanently stalled with the toggle ON; the bound
 * keeps the anti-hammer property, since a truly-broken client goes
 * quiet at the cap until a state-changing trigger arrives.
 * [onFailure] is a test-only observation hook.
 */
class PushRegistrationInteractor(
    private val backend: PushBackendClient,
    private val signer: PushSigner,
    private val attestation: PushAttestationProvider,
    private val preference: PushPreferenceProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val debounce: Duration = Duration.ofSeconds(2),
    private val refreshInterval: Duration = Duration.ofDays(3),
    /** First self-wake delay after a failed pass; doubles per
     * consecutive failure up to [failureRetryCap]. */
    private val failureRetryBase: Duration = Duration.ofSeconds(30),
    private val failureRetryCap: Duration = Duration.ofMinutes(15),
    private val onFailure: ((Throwable) -> Unit)? = null,
) {
    private val token = AtomicReference<String?>(null)
    private val subscriptions = AtomicReference<List<PushSubscription>?>(null)

    private val _state = MutableStateFlow<PushRegistrationState>(PushRegistrationState.Idle)

    /** The last pass's conclusion — see [PushRegistrationState]. The
     * app layer renders the Settings footnote from this; the durable
     * "backend holds a registration" bit stays
     * [PushPreferenceProvider.registeredFlow]. */
    val state: StateFlow<PushRegistrationState> = _state

    /** Consecutive failed passes — sizes the self-wake backoff; reset
     * by any pass that completes without throwing. Worker-loop
     * confined (passes are strictly serialized). */
    private var consecutiveFailures = 0

    /** At most ONE outstanding failure self-wake, ever — a burst of
     * failing triggers must not stack delayed retries. */
    private val retryScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Conflated: any number of triggers collapse into at most ONE
     * queued follow-up. The worker has already consumed the element
     * before the debounce delay, so a trigger landing mid-debounce
     * (or mid-pass) buffers one follow-up pass — harmless, passes
     * are idempotent and read current state at pass time. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (trigger in wake) {
                delay(debounce.toMillis())
                reconcile()
            }
        }
    }

    /** The full desired subscription set (ALL identities), or null
     * while identities haven't loaded yet. Replace-all semantics. */
    fun updateSubscriptions(subscriptions: List<PushSubscription>?) {
        this.subscriptions.set(subscriptions)
        if (subscriptions != null) wakeUp()
    }

    /** The current FCM registration token (initial fetch or
     * rotation). A rotation away from a registered token enqueues a
     * pending unregister for the old one. */
    fun updateToken(token: String) {
        this.token.set(token)
        wakeUp()
    }

    /** Flip the preference ON. The write happens HERE, before the
     * wake, so a pass can never read a stale value and act on the
     * old state — callers must not persist it themselves. Suspends
     * only for the preference write; the pass runs on [scope]. */
    suspend fun pushEnabled() {
        preference.setEnabled(true)
        wakeUp()
    }

    /** Flip the preference OFF; the write happens HERE, before the
     * wake (see [pushEnabled]). The pass asks the server to forget,
     * retried until it succeeds. Idempotent — safe to call at app
     * start to give a pending server-side forget a drain chance. */
    suspend fun pushDisabled() {
        preference.setEnabled(false)
        wakeUp()
    }

    private fun wakeUp() {
        wake.trySend(Unit)
    }

    private suspend fun reconcile() {
        try {
            // Quiet on every path — nothing recorded. A pass that is
            // transiently blocked (thrown network failure, retryable
            // refusal, a RETRY unregister outcome, a throttled
            // attestation) earns one delayed self-wake (see the class
            // KDoc's cadence contract); a deterministic rejection
            // earns none — resubmitting the identical request cannot
            // succeed, so only a state-changing trigger retries it.
            if (pass()) {
                consecutiveFailures = 0
                _state.value = when {
                    !preference.enabled() -> PushRegistrationState.Idle
                    preference.lastRegistrationFingerprint() != null ->
                        PushRegistrationState.Registered
                    else -> PushRegistrationState.Activating
                }
            } else {
                val willRetry = scheduleFailureRetry(bounded = false)
                _state.value = PushRegistrationState.Failed(code = null, willRetry = willRetry)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onFailure?.invoke(failure)
            // Classification shapes pacing only (see the class KDoc):
            // a deterministic refusal retries on the same backoff but
            // stops self-waking at the attempt bound.
            val rejected = failure as? PushBackendRejectedException
            val willRetry = scheduleFailureRetry(bounded = rejected?.deterministic == true)
            _state.value = PushRegistrationState.Failed(
                code = rejected?.code,
                willRetry = willRetry,
            )
        }
    }

    /** One delayed self-wake, exponential backoff from
     * [failureRetryBase] capped at [failureRetryCap], at most one
     * outstanding at a time. A [bounded] (deterministic) failure
     * schedules nothing beyond [DETERMINISTIC_RETRY_LIMIT]
     * consecutive failures. Answers whether a retry is (or already
     * was) outstanding. */
    private fun scheduleFailureRetry(bounded: Boolean): Boolean {
        consecutiveFailures += 1
        if (bounded && consecutiveFailures > DETERMINISTIC_RETRY_LIMIT) return false
        if (!retryScheduled.compareAndSet(false, true)) return true
        val shift = (consecutiveFailures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        val backoff = minOf(
            failureRetryBase.multipliedBy(1L shl shift),
            failureRetryCap,
        )
        scope.launch {
            delay(backoff.toMillis())
            retryScheduled.set(false)
            wakeUp()
        }
        return true
    }

    /** Answers whether the pass CONVERGED: true when the backend now
     * matches the desired state (or nothing can be done until a new
     * input arrives — no token/subscriptions yet); false when the
     * pass was blocked by a transient condition worth a self-wake. */
    private suspend fun pass(): Boolean {
        // Token rotation: the backend holds a token this device no
        // longer has. The old token becomes pending debt (durable,
        // before any attempt) and the stale registration record is
        // dropped so the new token registers below.
        val currentToken = token.get()
        val registeredToken = preference.lastRegisteredToken()
        if (registeredToken != null && currentToken != null && registeredToken != currentToken) {
            preference.setPendingUnregisterToken(registeredToken)
            preference.clearRegistration()
        }

        // Drain pending debt before ANY register: the server must
        // forget the old token before it is told about a new one. A
        // RETRY outcome gates the pass (transient — the next trigger
        // drains again); a deterministic refusal throws out of
        // [unregisterQuietly] with the debt KEPT — see the class
        // KDoc: only the attempt/age bound below ever expires a
        // debt, never the refusal's classification.
        val pending = preference.pendingUnregisterToken()
        if (pending != null) {
            if (debtExpired()) {
                preference.setPendingUnregisterToken(null)
            } else {
                preference.recordPendingUnregisterAttempt(clock())
                if (unregisterQuietly(pending) == UnregisterOutcome.RETRY) return false
                preference.setPendingUnregisterToken(null)
            }
        }

        if (!preference.enabled()) {
            // Disable: forget whatever the backend holds — from the
            // durable record, so this works when no live token exists.
            val target = preference.lastRegisteredToken() ?: return true
            preference.setPendingUnregisterToken(target)
            preference.clearRegistration()
            preference.recordPendingUnregisterAttempt(clock())
            if (unregisterQuietly(target) == UnregisterOutcome.RETRY) return false
            preference.setPendingUnregisterToken(null)
            return true
        }

        // Waiting for inputs, not blocked: the missing token or
        // subscription set arrives as its own trigger.
        val fcmToken = currentToken ?: return true
        val subs = subscriptions.get() ?: return true

        val digest = SignedPushPayload.subscriptionsDigest(subs)
        val fingerprint = registrationFingerprint(fcmToken, digest)
        val now = clock()
        if (fingerprint == preference.lastRegistrationFingerprint()) {
            val registeredAt = preference.lastRegisteredAt()
            val expiresAt = preference.registrationExpiresAt()
            val withinCadence = registeredAt != null &&
                now.isBefore(registeredAt.plus(refreshInterval))
            val outsideExpiryMargin = registeredAt == null || expiresAt == null ||
                now.isBefore(expiresAt.minus(refreshMargin(registeredAt, expiresAt)))
            if (withinCadence && outsideExpiryMargin) return true
        }

        // register session: challenge → payload → requestHash →
        // attestation → sign → registration key → seal → register.
        val challenge = freshChallenge("register")
        val userKey = signer.userKeyId()
        val timestamp = wireTimestamp(now)
        val payload = SignedPushPayload.register(
            challenge = challenge.challenge,
            userKey = userKey,
            timestamp = timestamp,
            fcmToken = fcmToken,
            subscriptions = subs,
        )
        val integrityToken = when (
            val attested = attestation.requestToken(SignedPushPayload.requestHash(payload))
        ) {
            is PushAttestationToken.Token -> attested.value
            // No usable Play environment: send without a token, the
            // backend decides.
            PushAttestationToken.Unsupported -> null
            // Rate-limited: retry later (the self-wake, or any
            // trigger). Never fails the enabled state — the toggle
            // stays on.
            PushAttestationToken.Throttled -> return false
        }
        val signature = signer.sign(payload)
        val serverKey = backend.fetchRegistrationKey()
        val envelope = PushTokenEnvelope.seal(fcmToken, serverKey.publicKey)
        // Inline padded Base64 (not Base64ByteArraySerializer) is
        // deliberate: `signature` is a String on the wire and this
        // matches the Rust fixtures' encoding exactly.
        val registration = backend.register(
            PushRegisterRequest(
                userKey = userKey,
                timestamp = timestamp,
                signature = Base64.getEncoder().encodeToString(signature),
                challenge = challenge.challenge,
                integrityToken = integrityToken,
                tokenEnvelope = envelope,
                subscriptions = subs,
            ),
        )

        // Reentrancy: the preference may have flipped OFF while the
        // register was suspended on the network. The server now holds
        // a registration the user just declined — convert it to
        // pending debt instead of recording it, and wake a drain.
        if (!preference.enabled()) {
            preference.setPendingUnregisterToken(fcmToken)
            wakeUp()
            return true
        }
        preference.recordRegistration(
            fingerprint = fingerprint,
            registeredAt = now,
            expiresAt = runCatching { PushJson.parseInstant(registration.expiresAt) }.getOrNull(),
            token = fcmToken,
        )
        return true
    }

    /** Whether the current debt has had its full chance:
     * [DEBT_ATTEMPT_LIMIT] attempts AND [DEBT_MAX_AGE] since the
     * first — both, so a device that retries slowly is not dropped
     * early (see the class KDoc). */
    private suspend fun debtExpired(): Boolean {
        if (preference.pendingUnregisterAttempts() < DEBT_ATTEMPT_LIMIT) return false
        val firstAttempt = preference.pendingUnregisterFirstAttemptAt() ?: return false
        return clock().isAfter(firstAttempt.plus(DEBT_MAX_AGE))
    }

    /** A challenge with a usable remainder of its ~600 s TTL: when
     * the fetched one arrives with under [CHALLENGE_MIN_REMAINING]
     * left (a slow network ate the window), one refetch — signing an
     * already-stale challenge earns a deterministic `bad_request` for
     * what is really a transient condition. An unparseable
     * `expiresAt` is used as-is; the backend stays the authority. */
    private suspend fun freshChallenge(purpose: String): IssuedPushChallenge {
        val challenge = backend.fetchChallenge(purpose)
        val expiresAt = runCatching { PushJson.parseInstant(challenge.expiresAt) }.getOrNull()
            ?: return challenge
        if (clock().isBefore(expiresAt.minus(CHALLENGE_MIN_REMAINING))) return challenge
        return backend.fetchChallenge(purpose)
    }

    /** How an unregister session for a stale token ended, and what
     * the caller owes the pending-debt slot for it. */
    private enum class UnregisterOutcome {
        /** The server confirmed — the debt is paid, clear it. */
        FORGOTTEN,

        /** Unreachable, rate-limited (429 / `capacity`), throttled
         * attestation, or any other transient condition — keep the
         * debt, the next trigger retries. */
        RETRY,
    }

    /** One full unregister session for [staleToken]. A DETERMINISTIC
     * refusal ([PushBackendRejectedException.deterministic]) is
     * rethrown — the debt stays (unregister is idempotent, so a
     * refusal never means "not registered here") and [reconcile]
     * paces the retry on the bounded backoff; every other failure is
     * swallowed into [UnregisterOutcome.RETRY]. */
    private suspend fun unregisterQuietly(staleToken: String): UnregisterOutcome = try {
        val challenge = freshChallenge("unregister")
        val userKey = signer.userKeyId()
        val timestamp = wireTimestamp(clock())
        val payload = SignedPushPayload.unregister(
            challenge = challenge.challenge,
            userKey = userKey,
            timestamp = timestamp,
            fcmToken = staleToken,
        )
        val integrityToken = when (
            val attested = attestation.requestToken(SignedPushPayload.requestHash(payload))
        ) {
            is PushAttestationToken.Token -> attested.value
            PushAttestationToken.Unsupported -> null
            PushAttestationToken.Throttled -> return UnregisterOutcome.RETRY
        }
        val signature = signer.sign(payload)
        val serverKey = backend.fetchRegistrationKey()
        // Inline padded Base64 on purpose — see the note at the
        // register call.
        backend.unregister(
            PushUnregisterRequest(
                userKey = userKey,
                timestamp = timestamp,
                signature = Base64.getEncoder().encodeToString(signature),
                challenge = challenge.challenge,
                integrityToken = integrityToken,
                tokenEnvelope = PushTokenEnvelope.seal(staleToken, serverKey.publicKey),
            ),
        )
        UnregisterOutcome.FORGOTTEN
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: PushBackendRejectedException) {
        if (refused.deterministic) throw refused
        onFailure?.invoke(refused)
        UnregisterOutcome.RETRY
    } catch (failure: Throwable) {
        onFailure?.invoke(failure)
        UnregisterOutcome.RETRY
    }

    companion object {
        /** Caps the backoff doubling arithmetic, not the retry count
         * — beyond this many consecutive failures the delay sits at
         * [failureRetryCap] anyway, and an unchecked shift would
         * overflow. */
        private const val MAX_BACKOFF_SHIFT = 10

        /** The debt-expiry bound (attempts AND age, both — see the
         * class KDoc): enough attempts to rule out a bad day, enough
         * age to rule out a fast burst. */
        internal const val DEBT_ATTEMPT_LIMIT = 8
        internal val DEBT_MAX_AGE: Duration = Duration.ofDays(7)

        /** Consecutive failures after which a DETERMINISTIC refusal
         * stops earning self-wakes — the anti-hammer bound; external
         * triggers still retry. */
        internal const val DETERMINISTIC_RETRY_LIMIT = 8

        /** Sign only a challenge with at least this much TTL left —
         * under it, one refetch (see [freshChallenge]). */
        internal val CHALLENGE_MIN_REMAINING: Duration = Duration.ofSeconds(60)

        /** Refresh this far before server expiry — at most 7 days, at
         * most half the granted window (a short-lived grant refreshes
         * at its midpoint rather than immediately). */
        internal fun refreshMargin(registeredAt: Instant, expiresAt: Instant): Duration {
            val window = Duration.between(registeredAt, expiresAt)
            return minOf(Duration.ofDays(7), window.dividedBy(2))
        }

        /** Local change detector: lowercase hex of
         * `sha256(sha256(tokenBytes) || subscriptionsDigest)`. Never
         * crosses the wire — the double hash keeps the raw token out
         * of the preference store. */
        internal fun registrationFingerprint(
            fcmToken: String,
            subscriptionsDigest: ByteArray,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(
                MessageDigest.getInstance("SHA-256").digest(fcmToken.encodeToByteArray()),
            )
            digest.update(subscriptionsDigest)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** RFC 3339 UTC, seconds precision — the form timestamps
         * enter signed payloads in. */
        internal fun wireTimestamp(now: Instant): String =
            now.truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
