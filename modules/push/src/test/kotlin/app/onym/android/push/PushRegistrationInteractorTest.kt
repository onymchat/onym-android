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

    private class FakeSigner : PushSigner {
        override suspend fun userKeyId(): String = "onym:key:aabb"
        override suspend fun sign(message: ByteArray): ByteArray = ByteArray(64) { 0x11 }
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
        val serverPublic: ByteArray =
            X25519PrivateKeyParameters(ByteArray(32) { 5 }, 0).generatePublicKey().encoded
        var expiresAt: String = "2026-09-21T12:00:00Z"
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

        override suspend fun fetchChallenge(purpose: String): IssuedPushChallenge {
            if (failChallenge) throw PushBackendUnreachableException("scripted outage")
            challengeCount += 1
            challengePurposes.add(purpose)
            return IssuedPushChallenge(ByteArray(32) { 0x42 }, "2026-08-22T12:10:00Z")
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
    ): PushRegistrationInteractor = PushRegistrationInteractor(
        backend = backend,
        signer = FakeSigner(),
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

    /** Finding 3: a deterministic refusal schedules NO self-wake —
     * resubmitting the identical request cannot succeed. */
    @Test
    fun `a deterministic register refusal does not self-retry`() = runTest {
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
        val challengesAfterFirstPass = backend.challengeCount

        // Even unbounded time drains no retry — no self-wake exists.
        backend.registerFailure = null
        advanceUntilIdle()
        assertEquals(challengesAfterFirstPass, backend.challengeCount)
        assertEquals(0, backend.registered.size)
        assertNull(preference.fingerprint)
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

    /** Finding 1 (PR #255, blocking): a pending unregister the
     * backend deterministically refuses is EXPIRED, not retried
     * forever — and the pass continues into the register instead of
     * staying wedged behind an unpayable debt. Also consumes the
     * typed error classification (finding 2). */
    @Test
    fun `a deterministically refused debt expires and the register proceeds`() = runTest {
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
        advanceUntilIdle()

        // The refused debt is gone, and the register went through.
        assertNull(preference.pendingUnregister)
        assertEquals(0, backend.unregistered.size)
        assertEquals(1, backend.registered.size)
        assertEquals("token-a", preference.registeredToken)
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
        val interactor = build(backend, preference)

        interactor.updateSubscriptions(subscriptions)
        interactor.updateToken("token-a")
        advanceUntilIdle()
        interactor.updateToken("token-b")
        advanceUntilIdle()

        assertEquals(1, backend.unregistered.size)
        assertEquals(2, backend.registered.size)
        assertEquals("token-b", preference.registeredToken)
        assertNull(preference.pendingUnregister)
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
