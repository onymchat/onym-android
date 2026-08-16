package app.onym.android.moderation

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Receipt for `POST /v1/mandates` on an Authority. `mandateRef` is the
 * SHA-256 of the mandate's canonical unsigned bytes and must agree
 * with the value this client computed before registration — the
 * repository enforces that, so a receipt for different bytes is never
 * recorded as registration of this consent.
 */
@Serializable
data class MandateRegistrationReceipt(
    val mandateRef: String,
    val accepted: Boolean,
)

/**
 * The Authority answered a non-2xx with its `{error, message}`
 * envelope. Deterministic-vs-transient is the caller's split (see
 * [isDeterministic]): a 400 can never become an acceptance through
 * exact replay, while a 503 or 429 may.
 */
class AuthorityRejectedException(
    val statusCode: Int,
    val rawCode: String?,
    override val message: String,
) : Exception(message) {
    /** A 4xx that exact replay can never turn into an acceptance —
     * excluding the transient pair (408 timeout, 429 rate limit).
     * Mirrors the iOS client's `isDeterministicStatusCode`. */
    val isDeterministic: Boolean
        get() = statusCode in 400..499 && statusCode != 408 && statusCode != 429
}

/** The Authority could not be reached, or answered unparseably —
 * retry territory, never a refusal. */
class AuthorityUnreachableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Client seam for a moderation Authority's mandate-registration
 * operation — the minimal Android slice of iOS's
 * `ModerationAuthorityClient` (reports, case responses, appeals and
 * recovery claims are deferred verticals; they extend this interface
 * when they land).
 *
 * The base URL comes from the [AuthorityListing] per call rather than
 * construction: the directory designates each authority's API
 * endpoint, and one process can hold mandates with different
 * authorities.
 */
interface AuthorityClient {
    /** `POST /v1/mandates` — body is the finalized mandate's exact
     * wire bytes (both signatures present). */
    suspend fun registerMandate(
        listing: AuthorityListing,
        mandate: ModerationMandate,
    ): MandateRegistrationReceipt
}

class OkHttpAuthorityClient(
    private val httpClient: OkHttpClient,
    /** Same debug-only emulator-loopback carve-out as
     * [OkHttpEnforcementBackendClient]; a release binary refuses
     * plain http. */
    private val allowInsecureLoopback: Boolean = false,
) : AuthorityClient {
    override suspend fun registerMandate(
        listing: AuthorityListing,
        mandate: ModerationMandate,
    ): MandateRegistrationReceipt = withContext(Dispatchers.IO) {
        val baseUrl = listing.apiBaseURL.trimEnd('/')
        val secure = baseUrl.startsWith("https://")
        val loopback = allowInsecureLoopback &&
            OkHttpEnforcementBackendClient.isLoopbackHost(baseUrl)
        if (!secure && !loopback) {
            // The listing is directory-served data; a bad entry must
            // degrade like an unreachable authority (registration
            // retried later), not throw out of a background retry.
            throw AuthorityUnreachableException(
                "authority API base URL must be https (or emulator loopback under a debug " +
                    "build): $baseUrl",
            )
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/mandates")
            .post(mandate.wireBytes().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        val (code, text) = try {
            httpClient.newCall(request).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        } catch (e: IOException) {
            throw AuthorityUnreachableException("authority unreachable", e)
        }

        if (code !in 200..299) {
            val envelope = runCatching {
                ModerationJson.json.decodeFromString(ErrorEnvelope.serializer(), text)
            }.getOrNull()
            throw AuthorityRejectedException(
                statusCode = code,
                rawCode = envelope?.error,
                message = envelope?.message ?: "authority answered HTTP $code",
            )
        }
        try {
            ModerationJson.json.decodeFromString(MandateRegistrationReceipt.serializer(), text)
        } catch (e: Exception) {
            // An unparseable 2xx may still have committed server-side;
            // classifying it retryable keeps the artifact for an exact
            // replay, which the authority accepts idempotently.
            throw AuthorityUnreachableException("authority answered unparseably", e)
        }
    }

    @Serializable
    private data class ErrorEnvelope(val error: String? = null, val message: String? = null)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
