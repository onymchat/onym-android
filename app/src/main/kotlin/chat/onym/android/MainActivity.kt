package chat.onym.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.fragment.app.FragmentActivity

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
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        val dependencies = (application as OnymApplication).dependencies
        setContent {
            MaterialTheme {
                RootScreen(dependencies = dependencies)
            }
        }
    }
}
