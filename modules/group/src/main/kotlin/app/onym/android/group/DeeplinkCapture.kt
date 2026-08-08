package app.onym.android.group

import android.content.Intent
import java.net.URI

/**
 * Glue between Android `Intent`s and the platform-agnostic
 * [IntroCapability.fromLink] decoder. Lives in `group/` next to the
 * capability type it decodes, so `transport/` doesn't depend on
 * `group/`.
 *
 * Two functions:
 *
 *  - [introCapabilityFromUri] is pure (`String?` in, `IntroCapability?`
 *    out) and unit-testable on JVM. Holds the **scheme/host
 *    allowlist** — the manifest intent-filters already gate inbound
 *    intents to `https://onym.app/join*` and `onym://join`, but a
 *    misconfigured launcher / share target could still surface other
 *    URIs to MainActivity. We re-check here as defense in depth and
 *    so the pre-flight reasoning lives in code, not in XML.
 *
 *  - [introCapabilityFromIntent] is the one-line Activity adapter.
 *    Called from MainActivity on cold start (`onCreate`) and warm
 *    start (`addOnNewIntentListener`).
 *
 * Returns `null` for non-deeplink intents so callers can fall through
 * to normal app launch without special-casing.
 */
object DeeplinkCapture {

    /** Allowlist of `(scheme, host)` pairs that may carry an
     *  [IntroCapability]. Mirrors the intent-filter shape declared
     *  in `AndroidManifest.xml`; keep the two in lockstep. */
    private val ALLOWED: Set<Pair<String, String>> = setOf(
        "https" to "onym.app",
        "onym" to "join",
    )

    /** Extract a capability from a raw URI string. Returns `null` for:
     *
     *  - non-onym URIs (host/scheme not on the allowlist),
     *  - URIs that parse but have no `c=` query parameter,
     *  - URIs with a malformed `c=` payload (bad base64, bad JSON,
     *    wrong byte sizes).
     *
     *  Pure JVM — no `android.net.Uri` dependency, so this can be
     *  unit-tested without Robolectric. */
    fun introCapabilityFromUri(rawUri: String?): IntroCapability? {
        if (rawUri.isNullOrEmpty()) return null
        val parsed = try { URI(rawUri) } catch (_: Throwable) { return null }
        val scheme = parsed.scheme?.lowercase() ?: return null
        val host = parsed.host?.lowercase() ?: return null
        if (scheme to host !in ALLOWED) return null
        return try { IntroCapability.fromLink(rawUri) } catch (_: Throwable) { null }
    }

    /** Activity adapter — pulls `intent.dataString` out and delegates
     *  to [introCapabilityFromUri]. Tolerates `null` intent (the case
     *  where the Activity was started without one, e.g. from the app
     *  launcher). */
    fun introCapabilityFromIntent(intent: Intent?): IntroCapability? =
        introCapabilityFromUri(intent?.dataString)
}
