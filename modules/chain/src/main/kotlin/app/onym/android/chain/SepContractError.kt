package app.onym.android.chain

import java.io.IOException

/**
 * Failure modes for [SepContractClient]. Sealed so `when`
 * exhaustiveness checks downstream don't need an `else` branch.
 *
 * Mirrors `SEPError` from onym-ios PR #24, plus a [NotConnected]
 * variant that the iOS twin folds into URLSession's own
 * `URLError.notConnectedToInternet`. We surface it explicitly so the
 * UI can render a distinct "offline" message vs. "server returned
 * 5xx".
 */
sealed class SepContractError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Server returned a non-2xx response. [body] is the captured
     *  response body (already decoded as UTF-8 if possible). */
    class BadStatus(val code: Int, val body: String) :
        SepContractError("contract call returned HTTP $code: $body")

    /** Response body didn't match the expected schema. */
    class MalformedResponse(cause: Throwable) :
        SepContractError("response body didn't match the expected schema", cause)

    /** Network-layer failure (DNS, TLS, connect, mid-flight read). */
    class NotConnected(cause: IOException) :
        SepContractError("network error: ${cause.message}", cause)
}
