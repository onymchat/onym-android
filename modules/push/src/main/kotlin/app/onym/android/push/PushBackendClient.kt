package app.onym.android.push

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The backend's deterministic error vocabulary (`{error, message}`
 * envelope). [UNKNOWN] absorbs codes a future backend grows — a
 * refusal this client can't classify is still a refusal. */
enum class PushBackendErrorCode {
    SIGNATURE_INVALID,
    RELAY_INVALID,
    CAPACITY,
    BAD_REQUEST,
    INTERNAL,
    UNKNOWN,
    ;

    companion object {
        fun fromRaw(raw: String?): PushBackendErrorCode = when (raw) {
            "signature_invalid" -> SIGNATURE_INVALID
            "relay_invalid" -> RELAY_INVALID
            "capacity" -> CAPACITY
            "bad_request" -> BAD_REQUEST
            "internal_error" -> INTERNAL
            else -> UNKNOWN
        }
    }
}

/** The push backend deterministically refused a request. Carries the
 * backend's own error vocabulary verbatim plus a typed classification
 * for the reconciler. */
class PushBackendRejectedException(
    val statusCode: Int,
    val rawCode: String?,
    override val message: String,
) : Exception(message) {
    val code: PushBackendErrorCode get() = PushBackendErrorCode.fromRaw(rawCode)
}

/** The backend could not be reached, or answered unparseably — a
 * retry-later condition, never a refusal. */
class PushBackendUnreachableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Client seam for the push backend (the Rust service behind
 * https://push-android.onym.app). JSON, https-only, no cookies, no
 * auth header — authentication is the in-body identity signature.
 */
interface PushBackendClient {
    /** `GET /v1/registration-key` — the current X25519 key to seal
     * the FCM token to. Fetched fresh before each register and
     * unregister. */
    suspend fun fetchRegistrationKey(): PushRegistrationKey

    /** `POST /v1/challenge` — a fresh single-use challenge for a
     * `"register"` or `"unregister"` session. */
    suspend fun fetchChallenge(purpose: String): IssuedPushChallenge

    /** `POST /v1/register`. */
    suspend fun register(request: PushRegisterRequest): PushRegistration

    /** `POST /v1/unregister` — idempotent. */
    suspend fun unregister(request: PushUnregisterRequest)
}

class OkHttpPushBackendClient(
    private val httpClient: OkHttpClient,
    baseUrl: String,
    /**
     * Accept `http://localhost` / `http://10.0.2.2` — the emulator's
     * loopback names for a dev backend. The app passes
     * `BuildConfig.DEBUG`, so a release binary refuses them; this
     * module has no build-type knowledge of its own.
     */
    private val allowInsecureLoopback: Boolean = false,
) : PushBackendClient {
    private val baseUrl: String = baseUrl.trimEnd('/')

    /**
     * Validated per request rather than thrown from `init`: this
     * client is constructed at dependency build (Application
     * onCreate), and a typo'd `PUSH_BASE_URL` must degrade like any
     * unreachable backend — into the reconciler's quiet retry — not
     * crash-loop the whole app at boot.
     */
    private fun validatedBaseUrl(): String {
        val secure = baseUrl.startsWith("https://")
        val loopback = allowInsecureLoopback && isLoopbackHost(baseUrl)
        if (!secure && !loopback) {
            throw PushBackendUnreachableException(
                "push backend base URL must be https (or emulator loopback under a debug " +
                    "build): $baseUrl",
            )
        }
        return baseUrl
    }

    override suspend fun fetchRegistrationKey(): PushRegistrationKey = execute(
        request = Request.Builder()
            .url(validatedBaseUrl() + "/v1/registration-key")
            .get()
            .header("Accept", "application/json")
            .build(),
        deserialize = { PushJson.json.decodeFromString(PushRegistrationKey.serializer(), it) },
    )

    override suspend fun fetchChallenge(purpose: String): IssuedPushChallenge = post(
        path = "/v1/challenge",
        body = PushJson.json.encodeToString(
            PushChallengeRequest.serializer(),
            PushChallengeRequest(purpose),
        ),
        deserialize = { PushJson.json.decodeFromString(IssuedPushChallenge.serializer(), it) },
    )

    override suspend fun register(request: PushRegisterRequest): PushRegistration = post(
        path = "/v1/register",
        body = PushJson.json.encodeToString(PushRegisterRequest.serializer(), request),
        deserialize = { PushJson.json.decodeFromString(PushRegistration.serializer(), it) },
    )

    override suspend fun unregister(request: PushUnregisterRequest) {
        post(
            path = "/v1/unregister",
            body = PushJson.json.encodeToString(PushUnregisterRequest.serializer(), request),
            // `{"status":"ok"}` — nothing the caller needs beyond the
            // 2xx itself, but decode it so a proxy interlude page on a
            // 200 still classifies as unreachable, not success.
            deserialize = { PushJson.json.decodeFromString(UnregisterAcknowledged.serializer(), it) },
        )
    }

    private suspend fun <T> post(
        path: String,
        body: String,
        deserialize: (String) -> T,
    ): T = execute(
        request = Request.Builder()
            .url(validatedBaseUrl() + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build(),
        deserialize = deserialize,
    )

    private suspend fun <T> execute(
        request: Request,
        deserialize: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val (code, text) = try {
            httpClient.newCall(request).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        } catch (e: IOException) {
            throw PushBackendUnreachableException("push backend unreachable", e)
        }

        if (code !in 200..299) {
            val envelope = runCatching {
                PushJson.json.decodeFromString(ErrorEnvelope.serializer(), text)
            }.getOrNull()
            throw PushBackendRejectedException(
                statusCode = code,
                rawCode = envelope?.error,
                message = envelope?.message ?: "push backend answered HTTP $code",
            )
        }
        try {
            deserialize(text)
        } catch (e: Exception) {
            // An unparseable 2xx is indistinguishable from a broken
            // deploy: retry-later territory, not a refusal.
            throw PushBackendUnreachableException("push backend answered unparseably", e)
        }
    }

    @Serializable
    private data class UnregisterAcknowledged(val status: String)

    @Serializable
    private data class ErrorEnvelope(val error: String? = null, val message: String? = null)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * A HOST comparison, not a prefix match — a prefix accepted
         * `http://localhost.attacker.example`. The host ends at the
         * first `:`/`/` (or end of string) and must equal a loopback
         * name exactly. Same rule as :moderation's
         * `OkHttpEnforcementBackendClient.isLoopbackHost`.
         */
        internal fun isLoopbackHost(url: String): Boolean {
            val rest = url.removePrefix("http://")
            if (rest == url) return false
            val host = rest.takeWhile { it != ':' && it != '/' }
            return host == "localhost" || host == "10.0.2.2"
        }
    }
}
