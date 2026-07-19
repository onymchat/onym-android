package app.onym.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import androidx.fragment.app.FragmentActivity
import app.onym.android.group.IntroCapability
import app.onym.android.group.LocalOnymTokens
import app.onym.android.group.OnymTokens
import app.onym.android.transport.DeeplinkCapture

/**
 * Sole entry point. Mounts [RootScreen] as the content; the recovery-
 * phrase backup flow is reachable via the Settings tab → Backup row.
 *
 * Extends [FragmentActivity] (not [ComponentActivity][androidx.activity.ComponentActivity])
 * because [androidx.biometric.BiometricPrompt] attaches its dialog
 * fragment to a `FragmentActivity` host.
 *
 * Sets [WindowManager.LayoutParams.FLAG_SECURE] on the window before
 * `super.onCreate` so screenshots / screen recording are blocked
 * across the whole app and the recents thumbnail is rendered as a
 * blank surface — stronger than iOS's scene-phase obscure overlay
 * (which only blanks the recents preview on backgrounding).
 *
 * Calls [enableEdgeToEdge] so the app draws under the system bars on
 * Android 14 and earlier (Android 15+ does this by default at
 * `targetSdkVersion 35+`). Compose [androidx.compose.material3.Scaffold]
 * downstream applies the matching `WindowInsets` padding so content
 * never sits under the status / navigation bars.
 *
 * Holds **no** repository or signer references — those live on
 * [OnymApplication.dependencies]. This Activity is purely a host
 * surface; the only thing it knows about the rest of the app is
 * that there is an [AppDependencies] to forward into [RootScreen].
 *
 * ## Deeplink intake (PR-6 of the deeplink-invite stack)
 *
 * Captures `https://onym.app/join?c=…` (App Link, autoVerify=true)
 * and `onym://join?c=…` (custom-scheme fallback) intents and threads
 * the decoded [IntroCapability] into [RootScreen] as a one-shot
 * `pendingCapability`. RootScreen consumes it (navigates to the
 * join destination) and acks via the `onPendingCapabilityHandled`
 * callback, which clears the slot so a back-navigation doesn't
 * re-trigger the same capability.
 *
 *  - **Cold start**: the launching intent is `getIntent()`. Captured
 *    once via `LaunchedEffect(Unit)` in the composable so the
 *    capability is available the first time RootScreen composes.
 *  - **Warm start**: subsequent intents arrive via
 *    [addOnNewIntentListener]. The listener is registered/unregistered
 *    inside `DisposableEffect` so it lives exactly as long as the
 *    setContent composition.
 *
 * Captured but unhandled capabilities are NOT lost on configuration
 * change — the `mutableStateOf` slot survives recomposition; only
 * the `onPendingCapabilityHandled` callback (after RootScreen
 * navigates) clears it.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Block screenshots / screen recording and blank the recents
        // thumbnail across the whole app. Suppressed only under the
        // in-process UI-test harness so fastlane screengrab can capture
        // the screen — FLAG_SECURE otherwise yields all-black images.
        // Gated the same way as every other harness seam (see
        // UITestRegistry); the registry is switched on by the test's
        // TestWatcher rule before this Activity launches.
        if (!UITestRegistry.enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        super.onCreate(savedInstanceState)
        val dependencies = (application as OnymApplication).dependencies
        setContent {
            var pending by remember { mutableStateOf<IntroCapability?>(null) }

            // Cold-start intake. `intent` is the Activity's launch
            // intent. Re-running this effect on Activity recreation
            // is intentional — `setIntent(...)` keeps the data URI
            // around, so the capability is recovered after
            // configuration change too.
            LaunchedEffect(Unit) {
                DeeplinkCapture.introCapabilityFromIntent(intent)?.let { pending = it }
            }

            // Warm-start intake. The OS calls `onNewIntent` on the
            // already-running Activity instead of restarting it
            // (because the launchMode default `standard` re-uses
            // the existing Activity for `singleTask`-equivalent
            // behavior in this app shell). The Compose-friendly
            // way to listen is via `addOnNewIntentListener` —
            // installed/uninstalled with the composition.
            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { newIntent ->
                    DeeplinkCapture.introCapabilityFromIntent(newIntent)?.let {
                        pending = it
                    }
                }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }

            // Follow the OS light/dark setting app-wide. A bare
            // `MaterialTheme {}` pins `lightColorScheme()`, which left
            // the shell (Chats / Settings) always-light while the
            // Create Group route — wrapped in `OnymTheme`, which reads
            // `isSystemInDarkTheme()` — flipped to dark on its own,
            // making it look like a stray dark scene in a light app.
            // Providing the scheme here is what RootScreen already
            // assumes ("Chats / Settings adapt via Material's
            // colorScheme").
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme()
                else lightColorScheme(),
            ) {
                // Provide the Onym token set app-wide, resolved from the OS
                // light/dark setting. Without this, `LocalOnymTokens`
                // defaults to `OnymTokens.Dark` everywhere outside the
                // Create Group flow (which has its own OnymTheme scope) —
                // so screens like Settings rendered dark tokens (e.g. the
                // Share/Backup/Delete buttons) even in light mode.
                CompositionLocalProvider(
                    LocalOnymTokens provides
                        if (isSystemInDarkTheme()) OnymTokens.Dark else OnymTokens.Light,
                ) {
                    RootScreen(
                        dependencies = dependencies,
                        pendingCapability = pending,
                        onPendingCapabilityHandled = { pending = null },
                    )
                }
            }
        }
    }
}
