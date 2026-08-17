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
 * Registration failed PERMANENTLY on receipt verification: the
 * Authority acknowledged a different `mandateRef`, or answered
 * `accepted: false`. Deterministic by definition — the same bytes get
 * the same receipt — so consent surfaces must classify this with
 * [AuthorityRejectedException]'s deterministic case (Review + reason,
 * retry only), never as the deferrable unavailability a transient
 * failure earns. Thrown by the repository, not the transport: the
 * checks hold for every [AuthorityClient] implementation.
 */
class AuthorityRegistrationRefusedException(override val message: String) : Exception(message)

/**
 * Client seam for a moderation Authority's user-facing operations —
 * the Android slice of iOS's `ModerationAuthorityClient` (case
 * responses, appeals and recovery claims are still deferred verticals;
 * they extend this interface when they land).
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

    /**
     * `POST /v1/reports` — body is the signed report's exact wire
     * bytes ([ModerationReport.wireBytes]), passed as bytes rather
     * than a value so a retry replays the persisted artifact
     * byte-for-byte (a filed report is immutable; the authority
     * refuses the same `reportId` with different bytes). No auth
     * headers — the report's own `signature` field is the
     * authentication.
     */
    suspend fun fileReport(
        listing: AuthorityListing,
        reportWireBytes: ByteArray,
    ): ReportReceipt

    /**
     * `PUT /v1/evidence-blobs/:sha256` — raw image bytes, uploaded
     * BEFORE the report that names them (a report naming an
     * un-uploaded digest is refused `media_missing`). [sha256] is the
     * lowercase hex digest of [bytes] — the plaintext digest the
     * accused committed to. The three signed headers are the party
     * credential (`evidence-blob:<sha256>:<timestamp>` signed by the
     * reporter's key); resource control, not integrity — integrity is
     * the digest. A 409 from this route means the bytes are already
     * on file, which callers treat as agreement, not failure.
     */
    suspend fun uploadEvidenceImage(
        listing: AuthorityListing,
        sha256: String,
        bytes: ByteArray,
        userKey: String,
        timestamp: String,
        signatureBase64: String,
    )
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
        val baseUrl = guardedBaseUrl(listing)
        val request = Request.Builder()
            .url("$baseUrl/v1/mandates")
            .post(mandate.wireBytes().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        val (code, text) = execute(request)
        if (code !in 200..299) throw rejection(code, text)
        try {
            ModerationJson.json.decodeFromString(MandateRegistrationReceipt.serializer(), text)
        } catch (e: Exception) {
            // An unparseable 2xx may still have committed server-side;
            // classifying it retryable keeps the artifact for an exact
            // replay, which the authority accepts idempotently.
            throw AuthorityUnreachableException("authority answered unparseably", e)
        }
    }

    override suspend fun fileReport(
        listing: AuthorityListing,
        reportWireBytes: ByteArray,
    ): ReportReceipt = withContext(Dispatchers.IO) {
        val baseUrl = guardedBaseUrl(listing)
        val request = Request.Builder()
            .url("$baseUrl/v1/reports")
            .post(reportWireBytes.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        val (code, text) = execute(request)
        if (code !in 200..299) throw rejection(code, text)
        try {
            ModerationJson.json.decodeFromString(ReportReceipt.serializer(), text)
        } catch (e: Exception) {
            // Same posture as registration: the filing may have
            // committed; keep the artifact and replay the exact bytes,
            // which the authority answers idempotently.
            throw AuthorityUnreachableException("authority answered unparseably", e)
        }
    }

    override suspend fun uploadEvidenceImage(
        listing: AuthorityListing,
        sha256: String,
        bytes: ByteArray,
        userKey: String,
        timestamp: String,
        signatureBase64: String,
    ): Unit = withContext(Dispatchers.IO) {
        val baseUrl = guardedBaseUrl(listing)
        val request = Request.Builder()
            .url("$baseUrl/v1/evidence-blobs/$sha256")
            .put(bytes.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("X-Onym-Key", userKey)
            .header("X-Onym-Timestamp", timestamp)
            .header("X-Onym-Signature", signatureBase64)
            .build()
        val (code, text) = execute(request)
        // The upload receipt's dimensions matter only to a sender
        // building a commitment; a reporter already holds them from
        // the message descriptor, so a 2xx body is not parsed here.
        if (code !in 200..299) throw rejection(code, text)
    }

    private fun guardedBaseUrl(listing: AuthorityListing): String {
        val baseUrl = listing.apiBaseURL.trimEnd('/')
        // ignoreCase: URL schemes are case-insensitive (RFC 3986 §3.1)
        // and OkHttp normalizes them — an `HTTPS://` directory entry
        // must not degrade into a permanently-retried "unreachable".
        val secure = baseUrl.startsWith("https://", ignoreCase = true)
        val loopback = allowInsecureLoopback &&
            OkHttpEnforcementBackendClient.isLoopbackHost(baseUrl)
        if (!secure && !loopback) {
            // The listing is directory-served data; a bad entry must
            // degrade like an unreachable authority (the operation
            // retried later), not throw out of a background retry.
            throw AuthorityUnreachableException(
                "authority API base URL must be https (or emulator loopback under a debug " +
                    "build): $baseUrl",
            )
        }
        return baseUrl
    }

    private fun execute(request: Request): Pair<Int, String> = try {
        httpClient.newCall(request).execute().use { response ->
            response.code to (response.body?.string() ?: "")
        }
    } catch (e: IOException) {
        throw AuthorityUnreachableException("authority unreachable", e)
    }

    private fun rejection(code: Int, text: String): AuthorityRejectedException {
        val envelope = runCatching {
            ModerationJson.json.decodeFromString(ErrorEnvelope.serializer(), text)
        }.getOrNull()
        return AuthorityRejectedException(
            statusCode = code,
            rawCode = envelope?.error,
            message = envelope?.message ?: "authority answered HTTP $code",
        )
    }

    @Serializable
    private data class ErrorEnvelope(val error: String? = null, val message: String? = null)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}
