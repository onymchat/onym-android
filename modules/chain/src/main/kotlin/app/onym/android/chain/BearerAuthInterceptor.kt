package app.onym.android.chain

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to an outbound request when
 * [token] is non-null and non-blank AND the request is bound for the
 * relayer's own origin. The relayer's `validate_auth`
 * (`onym-relayer/src/validation.rs`) requires this header on every
 * contract-call POST; without it, every request 401s.
 *
 * ## ONY-001 — the credential must never leave the relayer origin
 *
 * This interceptor used to attach the token to *every* request that
 * lacked an `Authorization` header. When the same [okhttp3.OkHttpClient]
 * was shared with the GitHub release fetchers, the Nostr relay fetcher
 * and — worst of all — the user-configurable Blossom media hosts, the
 * relayer bearer token leaked cross-origin to hosts the user (or an
 * attacker who can configure them) controls.
 *
 * The fix is twofold and defence-in-depth:
 *   1. Wiring (see `OnymApplication`): only the SEP contract / relayer
 *      transport is given a client carrying this interceptor. The
 *      GitHub / Nostr / Blossom paths use a plain, bearer-free client.
 *   2. [allowedHost]: even on the relayer client, the token is attached
 *      only when the request host matches the configured relayer origin.
 *      A `null` [allowedHost] preserves the legacy "attach to any host"
 *      behaviour and is intended for tests only; production always
 *      scopes to the relayer endpoint's host.
 *
 * Redirects: OkHttp already strips `Authorization` when following a
 * cross-host redirect (`RetryAndFollowUpInterceptor.buildRedirectRequest`
 * → `Util.canReuseConnectionFor` → `removeHeader("Authorization")`), and
 * this interceptor runs once on the original (relayer-host) request, so
 * a redirect can't re-attach the token off-origin. The [allowedHost]
 * check is the belt to that suspenders.
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
class BearerAuthInterceptor(
    private val token: String?,
    private val allowedHost: String? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val hostAllowed = allowedHost == null ||
            request.url.host.equals(allowedHost, ignoreCase = true)
        val withAuth = if (!token.isNullOrBlank() &&
            hostAllowed &&
            request.header("Authorization") == null
        ) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
        return chain.proceed(withAuth)
    }
}
