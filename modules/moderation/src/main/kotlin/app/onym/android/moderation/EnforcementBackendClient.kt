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
 * The enforcement backend deterministically refused a request. Carries
 * the backend's own error vocabulary (`{error, message}` envelope)
 * verbatim — `no_mandate` routes to re-consent, `signature_invalid`
 * and 401/403 to the refused path; everything else is the caller's to
 * classify.
 */
class BackendRejectedException(
    val statusCode: Int,
    val rawCode: String?,
    override val message: String,
) : Exception(message)

/** The backend could not be reached, or answered unparseably — the
 * grace window's territory, never a refusal. */
class BackendUnreachableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Client seam for the vendor's enforcement backend (the `android/`
 * service in onym-moderation). All POST, JSON, https-only, no cookies,
 * no auth header — authentication is the in-body identity signature.
 */
interface EnforcementBackendClient {
    /** `POST /v1/challenge` — a fresh single-use challenge for an
     * `"enroll"` or `"gate"` session. */
    suspend fun fetchChallenge(purpose: String): IssuedChallenge

    /** `POST /v1/enroll`. */
    suspend fun enrollDevice(request: EnrollmentRequest): DeviceEnrollment

    /** `POST /v1/mandates/countersign` — body is the mandate's exact
     * wire bytes; only a signature comes back. */
    suspend fun countersignMandate(mandateWireBytes: ByteArray): InterfaceCountersignature

    /** `POST /v1/gate-check`. */
    suspend fun gateCheck(request: GateCheckRequest): GateCheckResult
}

class OkHttpEnforcementBackendClient(
    private val httpClient: OkHttpClient,
    baseUrl: String,
    /**
     * Accept `http://localhost` / `http://10.0.2.2` — the emulator's
     * loopback names for a dev backend. The app passes
     * `BuildConfig.DEBUG`, so a release binary refuses them; this
     * module has no build-type knowledge of its own.
     */
    private val allowInsecureLoopback: Boolean = false,
) : EnforcementBackendClient {
    private val baseUrl: String = baseUrl.trimEnd('/')

    /**
     * Validated per request rather than thrown from `init`: this
     * client is constructed at dependency build (Application
     * onCreate), and a typo'd `MODERATION_BASE_URL` must degrade like
     * any unreachable backend — into the gate's grace arithmetic —
     * not crash-loop the whole app at boot.
     */
    private fun validatedBaseUrl(): String {
        val secure = baseUrl.startsWith("https://")
        val loopback = allowInsecureLoopback &&
            (baseUrl.startsWith("http://localhost") || baseUrl.startsWith("http://10.0.2.2"))
        if (!secure && !loopback) {
            throw BackendUnreachableException(
                "enforcement backend base URL must be https (or emulator loopback under a " +
                    "debug build): $baseUrl",
            )
        }
        return baseUrl
    }

    override suspend fun fetchChallenge(purpose: String): IssuedChallenge = post(
        path = "/v1/challenge",
        body = ModerationJson.json.encodeToString(
            ChallengeRequest.serializer(),
            ChallengeRequest(purpose),
        ),
        deserialize = { ModerationJson.json.decodeFromString(IssuedChallenge.serializer(), it) },
    )

    override suspend fun enrollDevice(request: EnrollmentRequest): DeviceEnrollment = post(
        path = "/v1/enroll",
        body = ModerationJson.json.encodeToString(EnrollmentRequest.serializer(), request),
        deserialize = { ModerationJson.json.decodeFromString(DeviceEnrollment.serializer(), it) },
    )

    override suspend fun countersignMandate(
        mandateWireBytes: ByteArray,
    ): InterfaceCountersignature = post(
        path = "/v1/mandates/countersign",
        body = mandateWireBytes.decodeToString(),
        deserialize = {
            ModerationJson.json.decodeFromString(InterfaceCountersignature.serializer(), it)
        },
    )

    override suspend fun gateCheck(request: GateCheckRequest): GateCheckResult = post(
        path = "/v1/gate-check",
        body = ModerationJson.json.encodeToString(GateCheckRequest.serializer(), request),
        deserialize = { ModerationJson.json.decodeFromString(GateCheckResult.serializer(), it) },
    )

    private suspend fun <T> post(
        path: String,
        body: String,
        deserialize: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(validatedBaseUrl() + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        val (code, text) = try {
            httpClient.newCall(request).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        } catch (e: IOException) {
            throw BackendUnreachableException("enforcement backend unreachable", e)
        }

        if (code !in 200..299) {
            val envelope = runCatching {
                ModerationJson.json.decodeFromString(ErrorEnvelope.serializer(), text)
            }.getOrNull()
            throw BackendRejectedException(
                statusCode = code,
                rawCode = envelope?.error,
                message = envelope?.message ?: "enforcement backend answered HTTP $code",
            )
        }
        try {
            deserialize(text)
        } catch (e: Exception) {
            // An unparseable 2xx is indistinguishable from a broken
            // deploy: the grace window's territory, not a refusal.
            throw BackendUnreachableException("enforcement backend answered unparseably", e)
        }
    }

    @Serializable
    private data class ChallengeRequest(val purpose: String)

    @Serializable
    private data class ErrorEnvelope(val error: String? = null, val message: String? = null)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
