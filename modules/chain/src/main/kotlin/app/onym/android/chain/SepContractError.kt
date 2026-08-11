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

    /**
     * The contract's own error number, when this failure carries one.
     *
     * A refused call comes back two different ways — a non-2xx whose
     * body holds the simulation output, or a 200 with
     * `accepted: false` and a message — and both embed the same
     * `Error(Contract, #N)` from the host. Reading the number turns
     * "the chain said no" into something a caller can act on.
     */
    val contractErrorCode: UInt?
        get() = (this as? BadStatus)?.let { SepContractErrorCode.parse(it.body) }
}

/**
 * Error numbers the SEP contracts return, as they appear in a Soroban
 * `HostError`. Mirrors the `#[contracterror] enum Error` in
 * `onym-contracts`; the raw values are the wire contract and only grow.
 *
 * Only the cases a client can say something useful about are named —
 * the rest stay numbers, because inventing friendly copy for a
 * condition we can't explain is worse than showing the diagnostic.
 */
enum class SepContractErrorCode(val code: UInt) {
    NOT_INITIALIZED(1u),
    ALREADY_INITIALIZED(2u),
    GROUP_ALREADY_EXISTS(4u),

    /**
     * The contract has no record of this group. Reached most often not
     * because something is broken but because it is *early*: the
     * `create_group` transaction has been submitted and has not yet
     * been included in a ledger, and the next call raced it.
     */
    GROUP_NOT_FOUND(5u),
    INVALID_PROOF(7u),
    INVALID_TIER(8u),
    PUBLIC_INPUTS_MISMATCH(10u),
    INVALID_EPOCH(11u),
    PROOF_REPLAY(12u),
    TIER_GROUP_LIMIT_REACHED(13u),
    ADMIN_ONLY(14u),
    INVALID_COMMITMENT_ENCODING(15u),
    ;

    companion object {
        private val MARKER = Regex("""Error\(Contract, #(\d+)\)""")

        /**
         * Pull the code out of a Soroban diagnostic blob.
         *
         * The body is a JSON-escaped dump of the host's event log, so
         * this scans for the `Error(Contract, #N)` marker rather than
         * trying to parse it. Text-matching a diagnostic is inherently
         * best-effort: a miss returns `null` and the caller falls back
         * to showing the raw message, which is no worse than today.
         */
        fun parse(body: String): UInt? =
            MARKER.find(body)?.groupValues?.getOrNull(1)?.toUIntOrNull()
    }
}
