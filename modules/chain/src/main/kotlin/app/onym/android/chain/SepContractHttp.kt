package app.onym.android.chain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * HTTP wiring for the one call in the app that waits on a chain
 * submission.
 *
 * A `create_group` / `update_commitment` POST makes the relayer shell
 * out to `stellar contract invoke`: simulate, sign, submit, then wait
 * for the transaction to land in a ledger (testnet closes one roughly
 * every five seconds). Measured against the deployed relayer, even a
 * request that fails during simulation costs 1.5–7 s, and a successful
 * one adds submission plus inclusion — routinely 10–20 s end to end.
 *
 * OkHttp's defaults (10 s connect / 10 s read / 10 s write) therefore
 * cut the call off mid-flight, which surfaces as
 * [SepContractError.NotConnected] → "Couldn't reach the relayer" at the
 * anchoring step of the create-group flow. The iOS twin posts the very
 * same payload through `URLSession.shared`, whose
 * `timeoutIntervalForRequest` default is 60 s, so the same flow survives
 * there. [TIMEOUT_SECONDS] holds Android to that budget.
 *
 * `callTimeout` is deliberately left at OkHttp's default (no cap): iOS
 * bounds each request at 60 s of *idleness*, not of total wall time, and
 * a chain round trip may legitimately run longer while still making
 * progress.
 *
 * Mirrors the session configuration in `SEPContractClient.swift`
 * (onym-ios).
 */
object SepContractHttp {

    /** Matches `URLSessionConfiguration.default.timeoutIntervalForRequest`. */
    const val TIMEOUT_SECONDS: Long = 60L

    /**
     * Derive the relayer client from the shared, bearer-free [base]:
     * reuse its connection pool and dispatcher, widen the timeouts to the
     * iOS-parity budget, then attach the bearer [token] scoped to
     * [endpointUrl]'s host.
     *
     * ONY-001: this is the only place the relayer credential is attached,
     * and only for the relayer's own origin — a cross-origin redirect
     * cannot carry it off (OkHttp also strips `Authorization` on
     * cross-host redirects).
     */
    fun relayerClient(
        base: OkHttpClient,
        token: String? = null,
        endpointUrl: String,
    ): OkHttpClient {
        val relayerHost = endpointUrl.toHttpUrlOrNull()?.host
        return base.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(BearerAuthInterceptor(token = token, allowedHost = relayerHost))
            .build()
    }
}
