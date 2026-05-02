package chat.onym.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.recovery.AndroidBiometricAuthenticator
import chat.onym.android.recovery.AndroidClipboardWriter
import chat.onym.android.recovery.AndroidStringProvider
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel

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
 */
class MainActivity : FragmentActivity() {

    private val repository: IdentityRepository by lazy {
        IdentityRepository(
            store = IdentitySecretStore(applicationContext)
        )
    }

    private val recoveryViewModel: RecoveryPhraseBackupViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return RecoveryPhraseBackupViewModel(
                    repository = repository,
                    authenticator = AndroidBiometricAuthenticator(
                        // Activity provider thunk — re-evaluated on
                        // each call so configuration-change recreation
                        // hands the prompt the new Activity instance
                        // rather than the captured-at-construction one.
                        activityProvider = { this@MainActivity },
                    ),
                    clipboard = AndroidClipboardWriter(applicationContext),
                    strings = AndroidStringProvider(applicationContext),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RootScreen(recoveryViewModel = recoveryViewModel)
            }
        }
    }
}
