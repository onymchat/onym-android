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
 * The subscription flow emits `null` until identities have actually
 * loaded — the composition root maps a not-yet-populated identity
 * list to null, never to an empty set, so a cold start can't
 * register "watch nothing" before the bootstrap lands.
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
    /** `NotificationManagerCompat.areNotificationsEnabled` in
     * production; injectable for tests. */
    private val notificationsEnabled: () -> Boolean,
) {

    fun start() {
        scope.launch {
            subscriptions.collect { interactor.updateSubscriptions(it) }
        }
        // Same-process token rotations from PushMessagingService.
        scope.launch {
            PushTokenRelay.tokens.collect { token ->
                if (token != null) interactor.updateToken(token)
            }
        }
        scope.launch {
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
            fetchToken()?.let { interactor.updateToken(it) }
            interactor.pushEnabled()
        }
    }

    /** Called AFTER POST_NOTIFICATIONS is granted (below API 33 it is
     * granted by install). */
    suspend fun enable() {
        preference.setEnabled(true)
        fetchToken()?.let { interactor.updateToken(it) }
        interactor.pushEnabled()
    }

    suspend fun disable() {
        preference.setEnabled(false)
        interactor.pushDisabled()
    }

    /** App start / resume: enabled-but-OS-blocked runs the full
     * disable path. */
    fun checkRevocation() {
        scope.launch {
            if (preference.enabled() && !notificationsEnabled()) disable()
        }
    }
}
