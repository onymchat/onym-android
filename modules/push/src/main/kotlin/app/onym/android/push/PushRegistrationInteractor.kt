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
import kotlinx.coroutines.launch

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
 * and cleared on success — or expired when the backend refuses the
 * unregister deterministically (see
 * [PushBackendRejectedException.deterministic]): such a refusal can
 * never succeed on retry, and holding the debt open would wedge every
 * future registration behind it. Every still-owed debt is drained
 * before any register. Disable therefore works from
 * `lastRegisteredToken` even when FCM no longer answers, an offline
 * disable is retried until the server confirms, and a disable that
 * lands while a register is suspended on the network converts the
 * just-registered token into pending debt instead of recording it.
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
 * the backend watching until an unrelated trigger happens to fire. A
 * pass that fails on a DETERMINISTIC backend rejection (see
 * [PushBackendRejectedException.deterministic]) schedules no
 * self-wake: resubmitting the identical request is pointless, and
 * only a state-changing trigger can make the next attempt different.
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

    /** Consecutive failed passes — sizes the self-wake backoff; reset
     * by any pass that completes without throwing. Worker-loop
     * confined (passes are strictly serialized). */
    private var consecutiveFailures = 0

    /** At most ONE outstanding failure self-wake, ever — a burst of
     * failing triggers must not stack delayed retries. */
    private val retryScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Conflated: any number of triggers collapse into at most one
     * queued pass. A trigger during the debounce window is absorbed
     * (its state is read at pass time anyway); a trigger during a
     * running pass queues exactly one follow-up. */
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
            } else {
                scheduleFailureRetry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onFailure?.invoke(failure)
            val deterministic =
                (failure as? PushBackendRejectedException)?.deterministic == true
            if (!deterministic) scheduleFailureRetry()
        }
    }

    /** One delayed self-wake, exponential backoff from
     * [failureRetryBase] capped at [failureRetryCap], at most one
     * outstanding at a time. */
    private fun scheduleFailureRetry() {
        consecutiveFailures += 1
        if (!retryScheduled.compareAndSet(false, true)) return
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
        // drains again); a REFUSED outcome clears the debt and lets
        // the pass continue — the backend refused the unregister
        // knowingly and deterministically, so the token was never
        // registered under this key (or the request is malformed) and
        // retrying it forever would wedge every future registration.
        val pending = preference.pendingUnregisterToken()
        if (pending != null) {
            if (unregisterQuietly(pending) == UnregisterOutcome.RETRY) return false
            preference.setPendingUnregisterToken(null)
        }

        if (!preference.enabled()) {
            // Disable: forget whatever the backend holds — from the
            // durable record, so this works when no live token exists.
            val target = preference.lastRegisteredToken() ?: return true
            preference.setPendingUnregisterToken(target)
            preference.clearRegistration()
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
        val challenge = backend.fetchChallenge("register")
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

    /** How an unregister session for a stale token ended, and what
     * the caller owes the pending-debt slot for it. */
    private enum class UnregisterOutcome {
        /** The server confirmed — the debt is paid, clear it. */
        FORGOTTEN,

        /** The server understood the request and refused it
         * deterministically ([PushBackendRejectedException.deterministic]):
         * the token was never registered under this key, or the
         * request is malformed. Retrying the identical request is
         * pointless — clear the debt so it cannot wedge every future
         * registration behind an unpayable refusal. */
        REFUSED,

        /** Unreachable, rate-limited (429 / `capacity`), throttled
         * attestation, or any other transient condition — keep the
         * debt, the next trigger retries. */
        RETRY,
    }

    /** One full unregister session for [staleToken]. Never throws;
     * the outcome says what to do with the pending debt. */
    private suspend fun unregisterQuietly(staleToken: String): UnregisterOutcome = try {
        val challenge = backend.fetchChallenge("unregister")
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
        onFailure?.invoke(refused)
        if (refused.deterministic) UnregisterOutcome.REFUSED else UnregisterOutcome.RETRY
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
