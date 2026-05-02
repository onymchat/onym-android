package chat.onym.android.chain

import android.content.res.Resources
import chat.onym.android.R
import java.io.IOException

/**
 * Resource-backed `(Throwable) -> String` factory injected into
 * [RelayerRepository] so the repo stays Compose / Context-free.
 * Production wires this from `OnymApplication` with
 * `applicationContext.resources`; tests can substitute a fake
 * lambda directly.
 *
 * Mirrors the iOS `localizedMessage(error:)` helper from PR #23.
 */
fun relayerFetchErrorMessageResolver(resources: Resources): (Throwable) -> String = { error ->
    when (error) {
        is RelayersFetchError.BadStatus ->
            resources.getString(R.string.relayer_fetch_failed_status, error.code)
        is RelayersFetchError.MalformedDocument ->
            resources.getString(R.string.relayer_fetch_failed_malformed)
        // IOException covers the OkHttp network-layer cases (no
        // route to host, DNS, TLS, timeout, etc.). A generic
        // catch-all falls back to the same offline message — better
        // than leaking a Throwable.message that mentions OkHttp.
        is IOException ->
            resources.getString(R.string.relayer_fetch_failed_offline)
        else ->
            resources.getString(R.string.relayer_fetch_failed_offline)
    }
}
