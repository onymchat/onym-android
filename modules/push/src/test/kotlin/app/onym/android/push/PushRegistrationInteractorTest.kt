@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.push

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciler under virtual time (no real sleeps): hand-written
 * fakes, an injected clock, and the durable bookkeeping asserted
 * through the same preference double production writes through.
 */
class PushRegistrationInteractorTest {

    /** Records the exact bytes it signed (PR #257 review, finding 1):
     * a constant answer alone structurally cannot witness WHAT was
     * signed, which let a mutated interactor sign the wrong payload
     * unnoticed. */
    private class FakeSigner : PushSigner {
        val signed = mutableListOf<ByteArray>()
        override suspend fun userKeyId(): String = "onym:key:aabb"
        override suspend fun sign(message: ByteArray): ByteArray {
            signed.add(message.copyOf())
            return ByteArray(64) { 0x11 }
        }
    }

    private class FakeAttestation : PushAttestationProvider {
        var answer: PushAttestationToken = PushAttestationToken.Token("integrity-fixture")
        val requestHashes = mutableListOf<String>()
        override suspend fun requestToken(requestHash: String): PushAttestationToken {
            requestHashes.add(requestHash)
            return answer
        }
    }

    private class ScriptedBackend : PushBackendClient {
        val serverPrivate = X25519PrivateKeyParameters(ByteArray(32) { 5 }, 0)
        val serverPublic: ByteArray = serverPrivate.generatePublicKey().encoded
        var expiresAt: String = "2026-09-21T12:00:00Z"
        var challengeExpiresAt: String = "2026-08-22T12:10:00Z"
        var failChallenge = false
        /** Thrown by unregister() while set — outage or scripted
         * refusal, the classification under test. */
        var unregisterFailure: Throwable? = null
        var registerFailure: Throwable? = null
        var registerGate: CompletableDeferred<Unit>? = null
        val registered = mutableListOf<PushRegisterRequest>()
        val unregistered = mutableListOf<PushUnregisterRequest>()
        var challengeCount = 0
        /** Purposes in fetch order — binds register→"register",
         * unregister→"unregister". */
        val challengePurposes = mutableListOf<String>()

        override suspend fun fetchRegistrationKey() = PushRegistrationKey(serverPublic)

        /** Counts fetch ATTEMPTS, including scripted outages —
         * [challengeCount] counts only issued challenges. */
        var challengeAttempts = 0

        override suspend fun fetchChallenge(purpose: String): IssuedPushChallenge {
            challengeAttempts += 1
            if (failChallenge) throw PushBackendUnreachableException("scripted outage")
            challengeCount += 1
            challengePurposes.add(purpose)
            return IssuedPushChallenge(ByteArray(32) { 0x42 }, challengeExpiresAt)
        }

        override suspend fun register(request: PushRegisterRequest): PushRegistration {
            registerGate?.await()
            registerFailure?.let { throw it }
            registered.add(request)
            return PushRegistration(expiresAt)
        }

        override suspend fun unregister(request: PushUnregisterRequest) {
            unregisterFailure?.let { throw it }
            unregistered.add(request)
        }
    }

    /** Runs exactly the next debounced pass — 100 ms debounce plus
     * slack — WITHOUT draining the scheduler, so a test can observe a
     * blocked pass before its self-wake retry fires. */
    private fun TestScope.runOnePass() {
        advanceTimeBy(200)
        runCurrent()
    }

    private val subscriptions = listOf(
        PushSubscription("a1b2c3d4e5f60718", listOf("wss://nostr.onym.app")),
    )

    private fun TestScope.build(
        backend: ScriptedBackend = ScriptedBackend(),
        preference: StaticPushPreferenceProvider = StaticPushPreferenceProvider(enabled = true),
        attestation: FakeAttestation = FakeAttestation(),
        clock: () -> Instant = { Instant.parse("2026-08-22T12:00:00Z") },
        refreshInterval: Duration = Duration.ofDays(3),
        signer: FakeSigner = FakeSigner(),
    ): PushRegistrationInteractor = PushRegistrationInteractor(
        backend = backend,
        signer = signer,
        attestation = attestation,
        preference = preference,
        // A plain scope on the test scheduler, NOT backgroundScope:
        // the worker's debounce delay must be a FOREGROUND task so
        // advanceUntilIdle drives it (background delays are exempt
        // from delay-skipping while the test body runs). The worker
        // parks on an empty channel between passes, so nothing leaks.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        clock = clock,
        debounce = Duration.ofMillis(100),
        refreshInterval = refreshInterval,
        onFailure = { it.printStackTrace() },
    )

    @Test
    fun `registers once every input is known`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
        val request = backend.registered.single()
        assertEquals("onym:key:aabb", request.userKey)
        assertEquals(subscriptions, request.subscriptions)
        assertEquals("integrity-fixture", request.integrityToken)
        assertEquals("token-a", preference.registeredToken)
        assertNotNull(preference.fingerprint)
        assertEquals(Instant.parse("2026-09-21T12:00:00Z"), preference.expiresAt)
        // One challenge, one register: the two triggers coalesced.
        assertEquals(1, backend.challengeCount)
        // And it was minted for the endpoint it authorizes.
        assertEquals(listOf("register"), backend.challengePurposes)
    }

    /** The outbound register request pinned end to end (PR #257
     * review, finding 1 — three authenticating fields no mutation
     * may drift): the challenge is the issued bytes, the signed
     * bytes are the payload the backend recomputes from the
     * request's own transmitted fields, and the sealed envelope
     * opens to the exact FCM token. */
    @Test
    fun `the register request carries what was challenged, signed and sealed`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val signer = FakeSigner()
        val interactor = build(backend, preference, signer = signer)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        val request = backend.registered.single()
        // (a) the challenge echoes the issued 32×0x42 bytes — not
        // zeros, not anything else.
        assertTrue(request.challenge.contentEquals(ByteArray(32) { 0x42 }))

        // (b) the signed bytes ARE the register payload recomputed
        // from the request's own fields — the backend's verification
        // arithmetic, run here.
        val expectedPayload = SignedPushPayload.register(
            challenge = request.challenge,
            userKey = request.userKey,
            timestamp = request.timestamp,
            fcmToken = "token-a",
            subscriptions = request.subscriptions,
        )
        assertTrue(signer.signed.single().contentEquals(expectedPayload))
        // ...and the transmitted signature is the signer's answer,
        // padded Base64.
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 0x11 }),
            request.signature,
        )

        // (c) the envelope opens, under the server's private key, to
        // the exact FCM token this device holds.
        assertEquals(
            "token-a",
            openPushTokenEnvelope(request.tokenEnvelope, backend.serverPrivate)
                .decodeToString(),
        )
    }

    /** The backend binds each challenge to its purpose and refuses a
     * swap — so the CALLER'S choice is what needs pinning: a register
     * session fetches a "register" challenge, an unregister session
     * an "unregister" one. */
    @Test
    fun `challenge purposes bind to the session that spends them`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        interactor.pushDisabled()
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
        assertEquals(1, backend.unregistered.size)
        assertEquals(listOf("register", "unregister"), backend.challengePurposes)
    }

    @Test
    fun `disabled means silence`() = runTest {
        val backend = ScriptedBackend()
        val interactor = build(backend, StaticPushPreferenceProvider(enabled = false))

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        assertEquals(0, backend.registered.size)
        assertEquals(0, backend.unregistered.size)
    }

    @Test
    fun `a token without a subscription set waits`() = runTest {
        val backend = ScriptedBackend()
        val interactor = build(backend)

        interactor.updateToken("token-a")
        advanceUntilIdle()

        assertEquals(0, backend.registered.size)
    }

    /** An empty LIST is meaningful — no identities, watch nothing —
     * and is sent, unlike null (not loaded), which waits. */
    @Test
    fun `an empty subscription list is sent`() = runTest {
        val backend = ScriptedBackend()
        val interactor = build(backend)

        interactor.updateToken("token-a")
        interactor.updateSubscriptions(emptyList())
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
        assertTrue(backend.registered.single().subscriptions.isEmpty())
    }

    @Test
    fun `an unchanged fingerprint sends nothing`() = runTest {
        val backend = ScriptedBackend()
        val interactor = build(backend)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        interactor.updateSubscriptions(subscriptions)
        interactor.pushEnabled()
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
    }

    @Test
    fun `a changed subscription set re-registers`() = runTest {
        val backend = ScriptedBackend()
        val interactor = build(backend)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        interactor.updateSubscriptions(
            subscriptions + PushSubscription("00ff00ff00ff00ff", listOf("wss://nostr.onym.app")),
        )
        advanceUntilIdle()

        assertEquals(2, backend.registered.size)
        assertEquals(2, backend.registered.last().subscriptions.size)
    }

    @Test
    fun `a failure records nothing and the next trigger retries`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.failChallenge = true
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()

        assertEquals(0, backend.registered.size)
        assertNull(preference.fingerprint)

        backend.failChallenge = false
        interactor.pushEnabled()
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
        assertNotNull(preference.fingerprint)
    }

    /** Finding 3 (PR #255): a blocked pass self-wakes — the retry
     * runs with NO external trigger, after the backoff delay. */
    @Test
    fun `a blocked pass retries itself after the backoff`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.failChallenge = true
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()
        assertEquals(0, backend.registered.size)

        // Nothing but time passes: the self-wake (30 s base backoff)
        // plus the debounce drives the retry on its own.
        backend.failChallenge = false
        advanceTimeBy(31_000)
        runCurrent()
        runOnePass()

        assertEquals(1, backend.registered.size)
        assertNotNull(preference.fingerprint)
    }

    /** Findings 1+2 rework (PR #255 round 2): a deterministic refusal
     * no longer suppresses the self-wake — a 4xx can be transient in
     * origin (a challenge outlived by a slow network), so it retries
     * on the same bounded backoff. */
    @Test
    fun `a deterministic register refusal still self-retries on the backoff`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.registerFailure = PushBackendRejectedException(
            statusCode = 400,
            rawCode = "bad_request",
            message = "scripted refusal",
        )
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()
        assertEquals(0, backend.registered.size)

        // Nothing but time: the bounded self-wake retries, and the
        // now-healthy backend accepts.
        backend.registerFailure = null
        advanceTimeBy(31_000)
        runCurrent()
        runOnePass()
        assertEquals(1, backend.registered.size)
        assertNotNull(preference.fingerprint)
    }

    /** ...but boundedly: after DETERMINISTIC_RETRY_LIMIT consecutive
     * failures the self-wake stops (the anti-hammer property), while
     * an external state-changing trigger still retries. */
    @Test
    fun `a deterministic refusal stops self-waking at the attempt bound`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.registerFailure = PushBackendRejectedException(
            statusCode = 400,
            rawCode = "signature_invalid",
            message = "scripted refusal",
        )
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        // Drains every self-wake there will ever be: the initial pass
        // plus DETERMINISTIC_RETRY_LIMIT bounded retries, then quiet.
        advanceUntilIdle()
        val expectedPasses = 1 + PushRegistrationInteractor.DETERMINISTIC_RETRY_LIMIT
        assertEquals(expectedPasses, backend.challengeAttempts)

        // More time buys nothing — the client has gone quiet.
        advanceTimeBy(Duration.ofHours(2).toMillis())
        runCurrent()
        advanceUntilIdle()
        assertEquals(expectedPasses, backend.challengeAttempts)

        // An external trigger is NOT bounded away, and succeeds once
        // the backend heals.
        backend.registerFailure = null
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)
        assertNotNull(preference.fingerprint)
    }

    /** The state flow (finding 3, PR #255): pass outcomes surface as
     * Idle / Registered / Failed(code, willRetry) — a terminal
     * refusal is diagnosable instead of "activating forever". */
    @Test
    fun `the state flow narrates the pass outcomes`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)
        assertEquals(PushRegistrationState.Idle, interactor.state.value)

        backend.registerFailure = PushBackendRejectedException(
            statusCode = 400,
            rawCode = "bad_request",
            message = "scripted refusal",
        )
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()
        assertEquals(
            PushRegistrationState.Failed(
                code = PushBackendErrorCode.BAD_REQUEST,
                willRetry = true,
            ),
            interactor.state.value,
        )

        // Exhaust the deterministic bound: willRetry flips false —
        // the Settings footnote's "check configuration" case.
        advanceUntilIdle()
        assertEquals(
            PushRegistrationState.Failed(
                code = PushBackendErrorCode.BAD_REQUEST,
                willRetry = false,
            ),
            interactor.state.value,
        )

        backend.registerFailure = null
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(PushRegistrationState.Registered, interactor.state.value)

        interactor.pushDisabled()
        advanceUntilIdle()
        assertEquals(PushRegistrationState.Idle, interactor.state.value)
    }

    /** The doubling itself (PR #257 review): after two consecutive
     * failures the SECOND retry waits 60 s — a mutant collapsing the
     * arithmetic to a flat 30 s base fires early and is caught by
     * the 31 s probe. Counters are SNAPSHOTTED while the backend is
     * failing and asserted only after it heals: a transient failure
     * reschedules itself unbounded, so an assertion thrown
     * mid-flight would leave runTest's cleanup draining a retry loop
     * that never goes idle. */
    @Test
    fun `the second retry waits sixty seconds not thirty`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.failChallenge = true
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()
        val afterFirstPass = backend.challengeAttempts

        // Retry #1 fires at 30 s and fails again.
        advanceTimeBy(31_000)
        runCurrent()
        runOnePass()
        val afterTheFirstBackoff = backend.challengeAttempts

        // Another 31 s: retry #2 must NOT have fired — it is due
        // 60 s after the second failure, not another 30.
        advanceTimeBy(31_000)
        runCurrent()
        runOnePass()
        val thirtyOneSecondsLater = backend.challengeAttempts

        // ...and by 60 s it does fire.
        advanceTimeBy(30_000)
        runCurrent()
        runOnePass()
        val sixtySecondsLater = backend.challengeAttempts

        backend.failChallenge = false
        advanceUntilIdle()

        assertEquals(1, afterFirstPass)
        assertEquals(2, afterTheFirstBackoff)
        assertEquals(2, thirtyOneSecondsLater)
        assertEquals(3, sixtySecondsLater)
        assertEquals(1, backend.registered.size)
    }

    /** The cap: the doubling stops at failureRetryCap (15 min) —
     * min(30 s × 2^5, cap) = cap, so the seventh attempt comes at
     * 900 s, not 960. Probe windows leave ~10 s of slack for the
     * debounce/slop drift the ladder accumulates, well under the
     * 60 s gaps being distinguished. (The deterministic ATTEMPT
     * bound is pinned in `a deterministic refusal stops self-waking
     * at the attempt bound`; transient failures like these retry
     * unbounded, hence the same snapshot-then-heal shape as above.) */
    @Test
    fun `the backoff doubling stops at the cap`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.failChallenge = true
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()
        // Walk the ladder: 30, 60, 120, 240, 480 s.
        for (delay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L)) {
            advanceTimeBy(delay + 1_000)
            runCurrent()
            runOnePass()
        }
        val afterTheLadder = backend.challengeAttempts

        // The sixth failure schedules min(960 s, 900 s) = the cap:
        // nothing 880 s in...
        advanceTimeBy(880_000)
        runCurrent()
        runOnePass()
        val beforeTheCap = backend.challengeAttempts
        // ...the retry lands at 900 s — and had the doubling gone
        // uncapped (960 s), this probe would still be too early.
        advanceTimeBy(25_000)
        runCurrent()
        runOnePass()
        val atTheCap = backend.challengeAttempts

        backend.failChallenge = false
        advanceUntilIdle()

        assertEquals(6, afterTheLadder)
        assertEquals(6, beforeTheCap)
        assertEquals(7, atTheCap)
        assertEquals(1, backend.registered.size)
    }

    /** Backend alignment: a 429/capacity refusal (the fixed-window
     * rate limits on challenge AND register) is fully retryable —
     * nothing recorded, nothing cleared, and the self-wake retries. */
    @Test
    fun `a capacity-refused register stays fully retryable`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.registerFailure = PushBackendRejectedException(
            statusCode = 429,
            rawCode = "capacity",
            message = "scripted rate limit",
        )
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()
        assertEquals(0, backend.registered.size)
        assertNull(preference.fingerprint)
        assertNull(preference.pendingUnregister)

        backend.registerFailure = null
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)
        assertNotNull(preference.fingerprint)
    }

    @Test
    fun `disable unregisters from the durable record when no live token exists`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = false)
        // A previous process registered; this one never saw a token.
        preference.fingerprint = "stale"
        preference.registeredToken = "token-old"
        val interactor = build(backend, preference)

        interactor.pushDisabled()
        advanceUntilIdle()

        assertEquals(1, backend.unregistered.size)
        assertEquals(0, backend.registered.size)
        assertNull(preference.pendingUnregister)
        assertNull(preference.fingerprint)
        assertNull(preference.registeredToken)
    }

    @Test
    fun `an offline disable keeps the pending debt and drains later`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        backend.unregisterFailure = PushBackendUnreachableException("scripted outage")
        interactor.pushDisabled()
        runOnePass()

        // Written BEFORE the attempt, kept on failure.
        assertEquals("token-a", preference.pendingUnregister)
        assertEquals(0, backend.unregistered.size)
        assertFalse(preference.enabled())

        backend.unregisterFailure = null
        interactor.pushDisabled()
        advanceUntilIdle()

        assertEquals(1, backend.unregistered.size)
        assertNull(preference.pendingUnregister)
    }

    /** Finding 3: the offline disable's swallowed failure still earns
     * the self-wake — the drain completes with no external trigger. */
    @Test
    fun `an offline disable drains by itself once the backend answers`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        backend.unregisterFailure = PushBackendUnreachableException("scripted outage")
        interactor.pushDisabled()
        runOnePass()
        assertEquals("token-a", preference.pendingUnregister)

        backend.unregisterFailure = null
        advanceTimeBy(31_000)
        runCurrent()
        runOnePass()

        assertEquals(1, backend.unregistered.size)
        assertNull(preference.pendingUnregister)
    }

    /** Finding 1 rework (PR #255 round 2, the reviewer's idempotency
     * argument): unregister answers 200 for unknown tokens, so a
     * deterministic refusal can never mean "not registered here" —
     * the debt SURVIVES the refusal, keeps gating the register, and
     * drains once the backend heals. */
    @Test
    fun `a deterministically refused debt survives and keeps gating`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        preference.pendingUnregister = "token-orphaned"
        val interactor = build(backend, preference)

        backend.unregisterFailure = PushBackendRejectedException(
            statusCode = 400,
            rawCode = "signature_invalid",
            message = "unknown user key",
        )
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()

        // The debt is kept — only the attempt was charged — and no
        // register may pass it.
        assertEquals("token-orphaned", preference.pendingUnregister)
        assertEquals(1, preference.pendingUnregisterAttempts)
        assertNotNull(preference.pendingUnregisterFirstAttemptAt)
        assertEquals(0, backend.registered.size)

        // Once the backend heals, the bounded self-wake drains the
        // debt first, then registers.
        backend.unregisterFailure = null
        advanceUntilIdle()
        assertNull(preference.pendingUnregister)
        assertEquals(1, backend.unregistered.size)
        assertEquals(1, backend.registered.size)
        assertEquals("token-a", preference.registeredToken)
    }

    /** Finding 1 rework: expiry is attempt count AND age, BOTH — so a
     * fast burst of failures cannot drop the debt young, and a debt
     * that aged without being pressed keeps its chances. */
    @Test
    fun `a debt expires only past the attempt count and the age together`() = runTest {
        suspend fun seededPreference(
            attempts: Int,
            firstAttemptAt: Instant,
        ): StaticPushPreferenceProvider {
            val preference = StaticPushPreferenceProvider(enabled = true)
            preference.pendingUnregister = "token-orphaned"
            repeat(attempts) { preference.recordPendingUnregisterAttempt(firstAttemptAt) }
            return preference
        }

        val now = Instant.parse("2026-08-22T12:00:00Z")
        val limit = PushRegistrationInteractor.DEBT_ATTEMPT_LIMIT
        val oldEnough = now.minus(PushRegistrationInteractor.DEBT_MAX_AGE.plusDays(1))

        // Enough attempts AND old enough: expired without touching
        // the wire; the register proceeds unwedged.
        run {
            val backend = ScriptedBackend()
            backend.unregisterFailure = PushBackendRejectedException(400, "signature_invalid", "x")
            val preference = seededPreference(limit, oldEnough)
            val interactor = build(backend, preference)
            interactor.updateSubscriptions(subscriptions)
            interactor.updateToken("token-a")
            advanceUntilIdle()
            assertNull(preference.pendingUnregister)
            assertEquals(0, backend.unregistered.size)
            assertEquals(1, backend.registered.size)
        }

        // Enough attempts but YOUNG: kept — slow retries are not
        // prematurely dropped just because a burst failed fast.
        run {
            val backend = ScriptedBackend()
            backend.unregisterFailure = PushBackendRejectedException(400, "signature_invalid", "x")
            val preference = seededPreference(limit, now.minus(Duration.ofHours(1)))
            val interactor = build(backend, preference)
            interactor.updateSubscriptions(subscriptions)
            interactor.updateToken("token-a")
            runOnePass()
            assertEquals("token-orphaned", preference.pendingUnregister)
            assertEquals(0, backend.registered.size)
        }

        // Old enough but FEW attempts: kept — age alone proves
        // nothing was tried.
        run {
            val backend = ScriptedBackend()
            backend.unregisterFailure = PushBackendRejectedException(400, "signature_invalid", "x")
            val preference = seededPreference(2, oldEnough)
            val interactor = build(backend, preference)
            interactor.updateSubscriptions(subscriptions)
            interactor.updateToken("token-a")
            runOnePass()
            assertEquals("token-orphaned", preference.pendingUnregister)
            assertEquals(0, backend.registered.size)
        }
    }

    /** Finding 2a (PR #255 round 2): a challenge arriving with under
     * a minute of TTL left is refetched before signing — a slow
     * network must not convert into a deterministic bad_request. */
    @Test
    fun `a nearly-expired challenge is refetched before signing`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        // 30 s of TTL left at the fixed test clock (12:00:00).
        backend.challengeExpiresAt = "2026-08-22T12:00:30Z"
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        // One session, two fetches: the stale first, the replacement
        // second — and the register still goes through.
        assertEquals(2, backend.challengeCount)
        assertEquals(1, backend.registered.size)
    }

    /** Findings 1+2 / backend alignment: a 429 capacity refusal of
     * the pending unregister is TRANSIENT — the debt survives and no
     * register may pass it. */
    @Test
    fun `a capacity-refused debt is kept and gates the register`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        preference.pendingUnregister = "token-old"
        val interactor = build(backend, preference)

        backend.unregisterFailure = PushBackendRejectedException(
            statusCode = 429,
            rawCode = "capacity",
            message = "scripted rate limit",
        )
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()

        assertEquals("token-old", preference.pendingUnregister)
        assertEquals(0, backend.registered.size)

        // Once capacity clears, the self-wake drains the debt first,
        // then registers.
        backend.unregisterFailure = null
        advanceUntilIdle()
        assertNull(preference.pendingUnregister)
        assertEquals(1, backend.unregistered.size)
        assertEquals(1, backend.registered.size)
    }

    /** Finding 5 (PR #255): the preference write lives inside
     * pushEnabled()/pushDisabled() — persisted before the pass, so
     * callers cannot leave a pass reading a stale value. */
    @Test
    fun `the interactor persists the preference itself`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = false)
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        assertEquals(0, backend.registered.size)

        interactor.pushEnabled()
        assertTrue(preference.enabled())
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)

        interactor.pushDisabled()
        assertFalse(preference.enabled())
        advanceUntilIdle()
        assertEquals(1, backend.unregistered.size)
    }

    @Test
    fun `token rotation unregisters the old token first`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val signer = FakeSigner()
        val interactor = build(backend, preference, signer = signer)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        interactor.updateToken("token-b")
        advanceUntilIdle()

        assertEquals(1, backend.unregistered.size)
        assertEquals(2, backend.registered.size)
        assertEquals("token-b", preference.registeredToken)
        assertNull(preference.pendingUnregister)

        // WHICH token was unregistered (PR #257 review, finding 1):
        // the debt is paid against the STALE token — provably, in
        // both the sealed envelope and the signed payload — not
        // against some token the server never held.
        val unregister = backend.unregistered.single()
        assertEquals(
            "token-a",
            openPushTokenEnvelope(unregister.tokenEnvelope, backend.serverPrivate)
                .decodeToString(),
        )
        val expectedUnregisterPayload = SignedPushPayload.unregister(
            challenge = unregister.challenge,
            userKey = unregister.userKey,
            timestamp = unregister.timestamp,
            fcmToken = "token-a",
        )
        assertTrue(signer.signed.any { it.contentEquals(expectedUnregisterPayload) })
    }

    /** The documented conflation semantics (PR #257 review, finding
     * 16): any number of triggers landing while a pass is RUNNING —
     * not merely queued — collapse into exactly one follow-up pass,
     * which reads the state current at ITS start. */
    @Test
    fun `triggers during a running pass coalesce into one follow-up`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.registerGate = CompletableDeferred()
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        // The pass is genuinely mid-flight, suspended inside
        // register() — after its challenge, before its response.
        assertEquals(1, backend.challengeCount)
        assertEquals(0, backend.registered.size)

        // Three triggers against the running pass, one of which
        // changes the desired state.
        val widened = subscriptions +
            PushSubscription("00ff00ff00ff00ff", listOf("wss://nostr.onym.app"))
        interactor.updateSubscriptions(widened)
        interactor.pushEnabled()
        interactor.pushEnabled()
        backend.registerGate!!.complete(Unit)
        backend.registerGate = null
        advanceUntilIdle()

        // Exactly ONE follow-up pass ran (2 sessions total, not 4),
        // and it asserted the widened set.
        assertEquals(2, backend.challengeCount)
        assertEquals(2, backend.registered.size)
        assertEquals(widened, backend.registered.last().subscriptions)
    }

    /** The reentrancy contract: a disable landing while the register
     * is suspended on the network must not record the registration —
     * the just-registered token becomes pending debt and is
     * unregistered. */
    @Test
    fun `disable during a suspended register ends unregistered`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val interactor = build(backend, preference)

        backend.registerGate = CompletableDeferred()
        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        // The pass is suspended inside register(). Flip the switch
        // off the way the Settings toggle does — through
        // pushDisabled(), which persists the preference itself — then
        // let the register complete.
        assertEquals(0, backend.registered.size)
        interactor.pushDisabled()
        backend.registerGate!!.complete(Unit)
        backend.registerGate = null
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
        assertNull("a declined registration must not be recorded", preference.fingerprint)
        assertEquals(1, backend.unregistered.size)
        assertNull(preference.pendingUnregister)
    }

    @Test
    fun `refreshes inside the expiry margin`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        var now = Instant.parse("2026-08-22T12:00:00Z")
        // 30-day window, margin = min(7d, 15d) = 7d; cadence longer
        // than the window so only the margin can trigger.
        backend.expiresAt = "2026-09-21T12:00:00Z"
        val interactor = build(
            backend,
            preference,
            clock = { now },
            refreshInterval = Duration.ofDays(90),
        )

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)

        // 22 days in: outside the margin, nothing sent.
        now = Instant.parse("2026-09-13T12:00:00Z")
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)

        // 24 days in: within 7 days of expiry — re-register.
        now = Instant.parse("2026-09-15T12:00:00Z")
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(2, backend.registered.size)
    }

    /** The min(7d, window/2) margin's OTHER branch: a short server
     * window (4 days) must refresh at its midpoint (margin 2d), not
     * at a flat 7 days — which would mean immediately, or with a
     * mutated flat margin, never early enough. */
    @Test
    fun `a short server window refreshes at its midpoint`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        var now = Instant.parse("2026-08-22T12:00:00Z")
        // expiresAt 4 days out ⇒ window 4d ⇒ margin = min(7d, 2d) = 2d.
        backend.expiresAt = "2026-08-26T12:00:00Z"
        val interactor = build(
            backend,
            preference,
            clock = { now },
            refreshInterval = Duration.ofDays(90),
        )

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)

        // 1 day 23 h in: before the midpoint — nothing sent. A flat
        // 7-day margin would already have re-registered here.
        now = Instant.parse("2026-08-24T11:00:00Z")
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)

        // Past the midpoint (2 days of the 4-day window left + 1 h):
        // inside the 2-day margin — re-register.
        now = Instant.parse("2026-08-24T13:00:00Z")
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(2, backend.registered.size)
    }

    @Test
    fun `refreshes after the cadence interval`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        var now = Instant.parse("2026-08-22T12:00:00Z")
        val interactor = build(
            backend,
            preference,
            clock = { now },
            refreshInterval = Duration.ofDays(3),
        )

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)

        now = Instant.parse("2026-08-26T12:00:00Z")
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(2, backend.registered.size)
    }

    /** A throttled attestation is a retry-later, never a failure of
     * the enabled state — and the eventual pass sends a token. */
    @Test
    fun `a throttled attestation retries without breaking enablement`() = runTest {
        val backend = ScriptedBackend()
        val preference = StaticPushPreferenceProvider(enabled = true)
        val attestation = FakeAttestation().apply { answer = PushAttestationToken.Throttled }
        val interactor = build(backend, preference, attestation)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        runOnePass()

        assertEquals(0, backend.registered.size)
        assertTrue(preference.enabled())
        assertNull(preference.fingerprint)

        attestation.answer = PushAttestationToken.Token("integrity-fixture")
        interactor.pushEnabled()
        advanceUntilIdle()
        assertEquals(1, backend.registered.size)
    }

    /** No Play environment: the request goes out WITHOUT a token —
     * the member on the wire is absent, not null. */
    @Test
    fun `an unsupported environment registers token-less`() = runTest {
        val backend = ScriptedBackend()
        val attestation = FakeAttestation().apply { answer = PushAttestationToken.Unsupported }
        val interactor = build(backend, attestation = attestation)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        assertEquals(1, backend.registered.size)
        assertNull(backend.registered.single().integrityToken)
    }

    /** The fingerprint's privacy invariant, pinned directly (PR #257
     * review): the exact double hash for fixed inputs — computed
     * here from first principles — and never the raw token as a
     * substring, which is precisely what the double hash exists to
     * keep out of the preference store. */
    @Test
    fun `the fingerprint is the double hash and never carries the token`() {
        val fcmToken = "token-a"
        val digest = SignedPushPayload.subscriptionsDigest(subscriptions)
        val fingerprint =
            PushRegistrationInteractor.registrationFingerprint(fcmToken, digest)

        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val inner = sha.digest(fcmToken.encodeToByteArray())
        sha.reset()
        sha.update(inner)
        sha.update(digest)
        val expected = sha.digest().joinToString("") { "%02x".format(it) }

        assertEquals(expected, fingerprint)
        assertEquals(64, fingerprint.length)
        assertFalse(fingerprint.contains(fcmToken))
    }

    /** The requestHash Play Integrity binds is the hash of the exact
     * signed bytes. */
    @Test
    fun `the attestation binds the signed payload's hash`() = runTest {
        val backend = ScriptedBackend()
        val attestation = FakeAttestation()
        val interactor = build(backend, attestation = attestation)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()

        val expected = SignedPushPayload.requestHash(
            SignedPushPayload.register(
                challenge = ByteArray(32) { 0x42 },
                userKey = "onym:key:aabb",
                timestamp = "2026-08-22T12:00:00Z",
                fcmToken = "token-a",
                subscriptions = subscriptions,
            ),
        )
        assertEquals(listOf(expected), attestation.requestHashes)
    }
}
