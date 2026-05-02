package chat.onym.android.chain

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to every outbound request
 * when [token] is non-null and non-blank. The relayer's
 * `validate_auth` (`onym-relayer/src/validation.rs`) requires this
 * header on every contract-call POST; without it, every request 401s.
 *
 * The "skip when blank" guard is deliberate — if a dev clones the
 * repo without pasting `relayer.authToken=…` into `local.properties`,
 * the empty `BuildConfig.RELAYER_AUTH_TOKEN` would otherwise produce
 * `Authorization: Bearer ` on the wire. The relayer rejects both, but
 * "no Authorization header" is a clearer failure signal than `Bearer ""`.
 *
 * Idempotent: requests that already carry an `Authorization` header
 * (e.g. some future auth flow we haven't designed yet) are passed
 * through untouched.
 *
 * Mirrors `BearerAuthInterceptor` from onym-ios PR #28.
 */
class BearerAuthInterceptor(private val token: String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val withAuth = if (!token.isNullOrBlank() && request.header("Authorization") == null) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
        return chain.proceed(withAuth)
    }
}
