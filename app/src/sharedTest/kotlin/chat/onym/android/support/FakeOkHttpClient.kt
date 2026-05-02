package chat.onym.android.support

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Test scaffolding for "talks to a real HTTP service" tests.
 *
 * Builds an [OkHttpClient] with an [Interceptor] that hands every
 * request to a caller-supplied handler — analogous to iOS's
 * `StubURLProtocol`. Returns canned [Response] objects per request
 * URL so tests can pin wire-format expectations without standing
 * up `MockWebServer` (which has its own thread, port-binding, and
 * shutdown semantics — overkill for unit tests).
 *
 * Reusable beyond this PR: any future "fetch JSON from a service"
 * test (chain client, push-notification gateway, telemetry) drops
 * into the same pattern.
 *
 * Mirrors `StubURLProtocol` from onym-ios PR #18.
 */
object FakeOkHttpClient {
    /** Build a client whose every request is handled by [handler]. */
    fun build(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    /** Canned 200 OK with a JSON body. */
    fun ok(request: Request, json: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    /** Canned non-success response with an empty body. */
    fun status(request: Request, code: Int, message: String = "Error"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
}
