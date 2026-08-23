package app.onym.android

import app.onym.android.push.PushPreferenceProvider
import app.onym.android.push.PushRegistrationInteractor
import app.onym.android.push.PushSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * App-layer conductor for the push seat: feeds the :push reconciler
 * its three inputs (the subscription set derived from ALL identities
 * plus the configured relays, the FCM token, and the on/off events)
 * and owns the enable/disable choreography the Settings toggle and
 * the launch-time revocation check call into.
 *
 * The subscription flow emits `null` until the identity and relay
 * stores have BOOTSTRAPPED — the composition root gates on each
 * store's first real load, not on emptiness — so a cold start can't
 * register "watch nothing" before the bootstrap lands, while a
 * genuinely empty set after load (last identity deleted, every relay
 * removed) flows through as `[]` and IS sent: that clearing register
 * is the one that tells the backend to stop watching.
 *
 * The system notification switch is the master: if the preference is
 * ON but the OS has notifications blocked for this app, the seat
 * runs the FULL disable path (preference off + server-side forget)
 * rather than sitting registered on a backend whose wakes can never
 * render — the iOS cold-launch revoke lesson. [checkRevocation] runs
 * on every app start and resume.
 */
class PushCoordinator(
    private val interactor: PushRegistrationInteractor,
    private val preference: PushPreferenceProvider,
    private val scope: CoroutineScope,
    private val subscriptions: Flow<List<PushSubscription>?>,
    /** Guarded FirebaseMessaging token fetch — answers null when
     * Firebase is unconfigured (no google-services.json) or the
     * fetch fails; the reconciler simply waits for a token. */
    private val fetchToken: suspend () -> String?,
    /** [PushMessagingService.notificationsRenderable] in production —
     * app-level notifications enabled AND the `messages` channel not
     * blocked, the same definition the render gate uses; injectable
     * for tests. */
    private val notificationsEnabled: () -> Boolean,
    /** Flips `FirebaseMessaging.isAutoInitEnabled` (a no-op stub when
     * Firebase is unconfigured). The manifest ships auto-init and
     * default data collection OFF, so nothing Firebase-shaped runs —
     * no Installations registration with Google, no unprompted token
     * — until the user opts in; the enable path turns auto-init on
     * (token rotations must keep flowing while opted in) and every
     * disable path turns it back off, symmetric with the server-side
     * unregister that already runs. */
    private val firebaseAutoInit: (Boolean) -> Unit = {},
    /** Synchronous write of the graph-free render-gate mirror
     * ([PushMessagingService.writeRenderGate] in production): the
     * service gates rendering on the user preference through a plain
     * SharedPreferences copy of the flag, because it must not pay
     * the composition root per delivery. Written on every
     * enable/disable and re-asserted at [start]. */
    private val renderGateMirror: (Boolean) -> Unit = {},
) {

    fun start() {
        scope.launch {
            subscriptions.collect {
                interactor.updateSubscriptions(it?.let(::capToBackendLimits))
            }
        }
        // Same-process token rotations from PushMessagingService.
        scope.launch {
            PushTokenRelay.tokens.collect { token ->
                if (token != null) interactor.updateToken(token)
            }
        }
        scope.launch {
            renderGateMirror(preference.enabled())
            if (!preference.enabled()) {
                // A pending server-side forget survives a relaunch —
                // give the reconciler a chance to drain it.
                interactor.pushDisabled()
                return@launch
            }
            if (!notificationsEnabled()) {
                disable()
                return@launch
            }
            // Re-assert opt-in side state (auto-init) — Firebase
            // persists it, but healing here keeps the pair of flags
            // from ever drifting apart across upgrades.
            firebaseAutoInit(true)
            fetchToken()?.let { interactor.updateToken(it) }
            interactor.pushEnabled()
        }
    }

    /** Called AFTER POST_NOTIFICATIONS is granted (below API 33 it is
     * granted by install). The preference write lives inside
     * [PushRegistrationInteractor.pushEnabled] — persisted before the
     * pass wakes, so a pass can never read a stale value.
     *
     * The channel gate that [checkRevocation] applies holds at the
     * door too: a user who blocked only the `messages` channel still
     * passes the POST_NOTIFICATIONS check, and without this guard the
     * toggle flipped on and registered — only for the next onStart's
     * revocation check to silently turn it back off. Refusing here
     * means nothing is persisted (the switch snaps back on its own,
     * same as a permission denial) and no device that can't render
     * is ever registered; the Settings host surfaces the guidance
     * toward channel settings. */
    suspend fun enable() {
        if (!notificationsEnabled()) return
        firebaseAutoInit(true)
        renderGateMirror(true)
        fetchToken()?.let { interactor.updateToken(it) }
        interactor.pushEnabled()
    }

    suspend fun disable() {
        // Mirror FIRST: a disable may take a while to reach the
        // server (retried until it confirms), and wakes arriving in
        // that window must already find the render gate closed.
        renderGateMirror(false)
        firebaseAutoInit(false)
        interactor.pushDisabled()
    }

    /** App start / resume: enabled-but-OS-blocked runs the full
     * disable path; enabled-and-renderable runs a refresh pass — the
     * interactor promises "a pass at every app start AND foreground",
     * and without this the foreground half didn't exist: a warm
     * process living past the server-granted window (Android keeps
     * them for days) let the registration lapse and wakes stop
     * silently, since [start] only runs at process launch.
     * [PushRegistrationInteractor.pushEnabled] is idempotent and
     * fingerprint/cadence-gated, so this is a no-op unless a refresh
     * is actually due. */
    fun checkRevocation() {
        scope.launch {
            if (!preference.enabled()) return@launch
            if (!notificationsEnabled()) {
                disable()
                return@launch
            }
            interactor.pushEnabled()
        }
    }

    companion object {
        /** The backend's configured default relay (`PUSH_DEFAULT_RELAY`
         * in onym-push `google/`), exempt from every server-side cap.
         * Prioritized when present in the user's configured list —
         * never injected into it: the backend must only watch relays
         * this device actually reads. */
        internal const val DEFAULT_RELAY = "wss://nostr.onym.app"

        // The backend's per-device relay caps, mirrored client-side
        // so a heavily-configured device truncates deterministically
        // instead of collecting relay_invalid/bad_request refusals.
        // Source: onym-push google/README.md ("Relay capacity is
        // three nested caps") and google/src/config.rs defaults —
        // PUSH_MAX_RELAYS_PER_TAG=4, PUSH_MAX_RELAYS_PER_DEVICE=8
        // (distinct hosts), PUSH_MAX_RELAYS_PER_HOST=4 (URLs per host,
        // per device). The global 50-URL pool cap is the server's
        // business (grandfathered upserts) and cannot be mirrored.
        internal const val MAX_RELAYS_PER_TAG = 4
        internal const val MAX_DISTINCT_HOSTS_PER_DEVICE = 8
        internal const val MAX_URLS_PER_HOST_PER_DEVICE = 4

        /**
         * Truncates a desired subscription set to what the backend's
         * per-device caps will accept: per tag, the default relay
         * first (when configured) then the user's relays in
         * configured order, cut at [MAX_RELAYS_PER_TAG]; across the
         * whole set, at most [MAX_DISTINCT_HOSTS_PER_DEVICE] distinct
         * non-default hosts and [MAX_URLS_PER_HOST_PER_DEVICE] URLs
         * per host. Deterministic: earlier tags and
         * earlier-configured relays win, so the same configuration
         * always registers the same set and the fingerprint
         * arithmetic stays stable.
         */
        internal fun capToBackendLimits(
            subscriptions: List<PushSubscription>,
        ): List<PushSubscription> {
            // Device-wide budget of accepted non-default URLs, by host.
            val acceptedByHost = mutableMapOf<String, MutableSet<String>>()
            return subscriptions.map { subscription ->
                val ordered =
                    subscription.relays.filter(::isDefaultRelay) +
                        subscription.relays.filterNot(::isDefaultRelay)
                val kept = mutableListOf<String>()
                for (url in ordered) {
                    if (kept.size >= MAX_RELAYS_PER_TAG) break
                    if (isDefaultRelay(url)) {
                        // Exempt from the host/URL budgets server-side.
                        if (url !in kept) kept.add(url)
                        continue
                    }
                    if (url in kept) continue
                    val host = hostOf(url)
                    val urls = acceptedByHost[host]
                    when {
                        urls == null ->
                            if (acceptedByHost.size < MAX_DISTINCT_HOSTS_PER_DEVICE) {
                                acceptedByHost[host] = mutableSetOf(url)
                                kept.add(url)
                            }
                        url in urls -> kept.add(url)
                        urls.size < MAX_URLS_PER_HOST_PER_DEVICE -> {
                            urls.add(url)
                            kept.add(url)
                        }
                    }
                }
                PushSubscription(tag = subscription.tag, relays = kept)
            }
        }

        /** Lowercased host of a `wss://host[:port][/path]` URL — the
         * unit the backend's per-device host cap counts. */
        private fun hostOf(url: String): String = url
            .substringAfter("://")
            .takeWhile { it != ':' && it != '/' }
            .lowercase()

        /** By HOST, not exact string: a user-configured
         * `wss://nostr.onym.app/` (trailing slash, or an uppercased
         * host) is still the default relay and must keep its
         * server-side cap exemption instead of consuming a host
         * slot. */
        internal fun isDefaultRelay(url: String): Boolean =
            hostOf(url) == hostOf(DEFAULT_RELAY)
    }
}
