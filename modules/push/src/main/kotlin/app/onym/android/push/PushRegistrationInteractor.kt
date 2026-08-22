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
 * Durability contract (see [PushPreferenceProvider]): a token the
 * backend must forget — disable, or the OLD token on rotation — is
 * written to `pendingUnregisterToken` BEFORE the unregister attempt
 * and cleared only on success, and every pending debt is drained
 * before any register. Disable therefore works from
 * `lastRegisteredToken` even when FCM no longer answers, an offline
 * disable is retried until the server confirms, and a disable that
 * lands while a register is suspended on the network converts the
 * just-registered token into pending debt instead of recording it.
 *
 * Failures are quiet — no user-activity logging (privacy: the
 * backend registration IS activity metadata) — and simply leave the
 * state to be retried by the next trigger. [onFailure] is a test-only
 * observation hook.
 */
class PushRegistrationInteractor(
    private val backend: PushBackendClient,
    private val signer: PushSigner,
    private val attestation: PushAttestationProvider,
    private val preference: PushPreferenceProvider,
    scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val debounce: Duration = Duration.ofSeconds(2),
    private val refreshInterval: Duration = Duration.ofDays(3),
    private val onFailure: ((Throwable) -> Unit)? = null,
) {
    private val token = AtomicReference<String?>(null)
    private val subscriptions = AtomicReference<List<PushSubscription>?>(null)

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

    /** The preference flipped ON (already persisted by the caller). */
    fun pushEnabled() {
        wakeUp()
    }

    /** The preference flipped OFF (already persisted by the caller).
     * The pass asks the server to forget, retried until it
     * succeeds. */
    fun pushDisabled() {
        wakeUp()
    }

    private fun wakeUp() {
        wake.trySend(Unit)
    }

    private suspend fun reconcile() {
        try {
            pass()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Quiet: nothing recorded, the next trigger retries.
            onFailure?.invoke(failure)
        }
    }

    private suspend fun pass() {
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
        // forget the old token before it is told about a new one.
        val pending = preference.pendingUnregisterToken()
        if (pending != null) {
            if (!unregisterQuietly(pending)) return
            preference.setPendingUnregisterToken(null)
        }

        if (!preference.enabled()) {
            // Disable: forget whatever the backend holds — from the
            // durable record, so this works when no live token exists.
            val target = preference.lastRegisteredToken() ?: return
            preference.setPendingUnregisterToken(target)
            preference.clearRegistration()
            if (!unregisterQuietly(target)) return
            preference.setPendingUnregisterToken(null)
            return
        }

        val fcmToken = currentToken ?: return
        val subs = subscriptions.get() ?: return

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
            if (withinCadence && outsideExpiryMargin) return
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
            // Rate-limited: retry later. Never fails the enabled
            // state — the toggle stays on, the next trigger retries.
            PushAttestationToken.Throttled -> return
        }
        val signature = signer.sign(payload)
        val serverKey = backend.fetchRegistrationKey()
        val envelope = PushTokenEnvelope.seal(fcmToken, serverKey.publicKey)
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
            return
        }
        preference.recordRegistration(
            fingerprint = fingerprint,
            registeredAt = now,
            expiresAt = runCatching { PushJson.parseInstant(registration.expiresAt) }.getOrNull(),
            token = fcmToken,
        )
    }

    /** One full unregister session for [staleToken]. Answers false on
     * any failure (including attestation throttle) — the pending debt
     * stays set and the next trigger retries. */
    private suspend fun unregisterQuietly(staleToken: String): Boolean = try {
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
            PushAttestationToken.Throttled -> return false
        }
        val signature = signer.sign(payload)
        val serverKey = backend.fetchRegistrationKey()
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
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onFailure?.invoke(failure)
        false
    }

    companion object {
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
