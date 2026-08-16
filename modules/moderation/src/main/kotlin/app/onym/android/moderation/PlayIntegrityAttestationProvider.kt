package app.onym.android.moderation

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Sliding-window guard for Google's limit of at most five provider
 * preparations per app instance per minute (profile §5.2). Injectable
 * clock so the arithmetic is unit-testable.
 */
class PrepareRateLimiter(
    private val limit: Int = 5,
    private val window: Duration = Duration.ofMinutes(1),
    private val clock: () -> Instant = Instant::now,
) {
    private val attempts = ArrayDeque<Instant>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (attempts.isNotEmpty() && Duration.between(attempts.first(), now) > window) {
            attempts.removeFirst()
        }
        if (attempts.size >= limit) return false
        attempts.addLast(now)
        return true
    }
}

/**
 * Production [DeviceAttestationProvider] over Play Integrity's
 * standard requests.
 *
 * One prepared [StandardIntegrityTokenProvider] is held and reused;
 * it is re-prepared lazily on `INTEGRITY_TOKEN_PROVIDER_INVALID`.
 * Token requests are single-flight (the gate scheduler already
 * coalesces triggers; the mutex is the hard guarantee), preparations
 * are capped by [PrepareRateLimiter], and `TOO_MANY_REQUESTS` starts
 * an exponential backoff during which requests answer
 * [AttestationToken.Throttled] without touching Play. Unsupported
 * environments (emulator, de-Googled, outdated Play) answer
 * [AttestationToken.Unsupported] — the backend, not this client,
 * decides what a token-less session means.
 */
class PlayIntegrityAttestationProvider(
    context: Context,
    private val cloudProjectNumber: Long,
    private val rateLimiter: PrepareRateLimiter = PrepareRateLimiter(),
    private val clock: () -> Instant = Instant::now,
) : DeviceAttestationProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null
    private var backoffUntil: Instant = Instant.EPOCH
    private var backoff: Duration = INITIAL_BACKOFF
    /** Set once a definitive "no Play environment" answer arrives;
     * cleared only by process restart (these conditions do not repair
     * themselves mid-session). */
    private var unsupported = false

    override suspend fun requestToken(requestHash: String): AttestationToken = mutex.withLock {
        if (unsupported) return AttestationToken.Unsupported
        if (clock().isBefore(backoffUntil)) return AttestationToken.Throttled

        val prepared = provider ?: prepare() ?: return pendingOutcome()
        try {
            val token = prepared.request(
                StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build(),
            ).await()
            backoff = INITIAL_BACKOFF
            AttestationToken.Token(token.token())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StandardIntegrityException) {
            when (e.errorCode) {
                StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID -> {
                    // The held provider lapsed; re-prepare once, in
                    // this same slot, then retry the request.
                    provider = null
                    val fresh = prepare() ?: return pendingOutcome()
                    return try {
                        val token = fresh.request(
                            StandardIntegrityTokenRequest.builder()
                                .setRequestHash(requestHash)
                                .build(),
                        ).await()
                        // Success resets the backoff on this path too,
                        // matching the primary request path.
                        backoff = INITIAL_BACKOFF
                        AttestationToken.Token(token.token())
                    } catch (retry: kotlinx.coroutines.CancellationException) {
                        throw retry
                    } catch (retry: StandardIntegrityException) {
                        classify(retry)
                    } catch (_: Exception) {
                        AttestationToken.Throttled
                    }
                }
                else -> classify(e)
            }
        } catch (_: Exception) {
            // Same rationale as prepare(): a non-Integrity Task
            // failure is an attestation-side condition, not network
            // unreachability.
            AttestationToken.Throttled
        }
    }

    private suspend fun prepare(): StandardIntegrityTokenProvider? {
        if (!rateLimiter.tryAcquire()) return null
        return try {
            manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build(),
            ).await().also { provider = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StandardIntegrityException) {
            classify(e)
            null
        } catch (e: Exception) {
            // A generic Tasks failure escaping to performAttempt's
            // catch-all would be reported as backend unreachability
            // (offlineGraceExpired past grace) — but nothing was on
            // the network. Throttled is honest: grace serves, and the
            // eventual reason is attestationUnavailable.
            null
        }
    }

    /** A prepare that could not run (rate cap) or failed: what the
     * caller should report, given the flags `classify` set. */
    private fun pendingOutcome(): AttestationToken =
        if (unsupported) AttestationToken.Unsupported else AttestationToken.Throttled

    private fun classify(e: StandardIntegrityException): AttestationToken = when (e.errorCode) {
        // Latched: these do not repair themselves while the process
        // lives (no Play at all; a build-time misconfiguration).
        StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
        StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
        StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
        -> {
            unsupported = true
            AttestationToken.Unsupported
        }
        // NOT latched: the user can update Play Store / Play services
        // (or the API can become available) without restarting this
        // process, and a latch would pin every later session
        // token-less. Answer honestly this time, ask Play again next.
        StandardIntegrityErrorCode.API_NOT_AVAILABLE,
        StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
        StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        -> AttestationToken.Unsupported
        StandardIntegrityErrorCode.TOO_MANY_REQUESTS -> {
            backoffUntil = clock().plus(backoff)
            backoff = minOf(backoff.multipliedBy(2), MAX_BACKOFF)
            AttestationToken.Throttled
        }
        // Transient (network, internal, app-not-installed races):
        // treat as throttled — grace serves the last state and the
        // next slot retries.
        else -> AttestationToken.Throttled
    }

    private companion object {
        val INITIAL_BACKOFF: Duration = Duration.ofSeconds(30)
        val MAX_BACKOFF: Duration = Duration.ofMinutes(15)
    }
}
