package app.onym.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one crossing point between [PushMessagingService] and the app's
 * dependency graph. FCM instantiates the service on ITS schedule —
 * possibly in a process spun up only to deliver a token — and the
 * service must stay graph-free and cheap (it must never force
 * [OnymApplication.dependencies] to build). So `onNewToken` drops the
 * token here, and [app.onym.android.push.PushRegistrationInteractor]
 * receives it through the coordinator's collector whenever the graph
 * does exist. A conflated StateFlow is exactly the right loss
 * semantics: only the newest token is ever worth registering.
 *
 * Deliberately NOT persisted and NOT logged — the token is fetched
 * fresh from FirebaseMessaging when the preference is on, so this
 * slot only bridges same-process rotations.
 */
object PushTokenRelay {
    private val state = MutableStateFlow<String?>(null)

    val tokens: StateFlow<String?> = state

    fun offer(token: String) {
        state.value = token
    }
}
