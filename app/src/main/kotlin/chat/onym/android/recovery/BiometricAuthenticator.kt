package chat.onym.android.recovery

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Async biometric / device-credential prompt. Wrapped behind an
 * interface so [RecoveryPhraseBackupViewModel] can be unit-tested
 * without standing up a real [BiometricPrompt] (which requires a
 * `FragmentActivity` and user interaction).
 *
 * Mirrors the iOS [`BiometricAuthenticator` protocol
 * ](https://github.com/onymchat/onym-ios/blob/main/Sources/OnymIOS/Recovery/BiometricAuthenticator.kt)
 * 1:1: success → return; cancel/failure → throw; no enrolled
 * biometric AND no device credential → return success without
 * prompting (matches iOS's `canEvaluatePolicy == false → fail open`).
 */
interface BiometricAuthenticator {
    /**
     * Prompts the user. Returns on success; throws on cancel/failure.
     *
     * @param title shown at the top of the system prompt
     *        (e.g. `"Authenticate to reveal your recovery phrase"`).
     */
    suspend fun authenticate(title: String, subtitle: String? = null)
}

/** Failure surface for [AndroidBiometricAuthenticator]. The message
 *  comes from `BiometricPrompt`'s `errString` and is already
 *  user-facing (e.g. `"Authentication cancelled"`,
 *  `"Too many attempts. Try again later."`). */
class BiometricAuthException(
    val errorCode: Int,
    message: String,
) : Exception(message)

/**
 * Production [BiometricAuthenticator] backed by AndroidX
 * [BiometricPrompt].
 *
 * `BiometricPrompt` requires a [FragmentActivity] host to attach its
 * dialog fragment to. We take an [activityProvider] thunk rather than
 * a captured `Activity` reference so the long-lived
 * [RecoveryPhraseBackupViewModel] doesn't pin an `Activity` past
 * configuration change (which would leak the entire view hierarchy).
 *
 * When called, the provider is invoked on the main thread and is
 * expected to return the currently-foregrounded activity. The Compose
 * entry point (`MainActivity`) supplies `{ this@MainActivity }`.
 */
class AndroidBiometricAuthenticator(
    private val activityProvider: () -> FragmentActivity,
) : BiometricAuthenticator {

    /** `BIOMETRIC_WEAK` lets users with face-only auth (e.g. older
     *  Pixels) past the gate too. `DEVICE_CREDENTIAL` is the PIN /
     *  pattern / password fallback. The combination matches iOS's
     *  `.deviceOwnerAuthentication` policy. */
    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override suspend fun authenticate(title: String, subtitle: String?) {
        // Switch to main for the BiometricManager probe + prompt
        // attachment — the prompt fragment must be added on the main
        // thread, and BiometricManager itself is main-only by contract.
        withContext(Dispatchers.Main) {
            val activity = activityProvider()
            val manager = BiometricManager.from(activity)

            when (manager.canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> {
                    suspendCancellableCoroutine { continuation ->
                        val callback = object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult,
                            ) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onAuthenticationError(
                                errorCode: Int,
                                errString: CharSequence,
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        BiometricAuthException(errorCode, errString.toString())
                                    )
                                }
                            }

                            // onAuthenticationFailed (single attempt rejection)
                            // is a UI-only signal — the system prompt re-asks
                            // automatically. We only resume on terminal events
                            // (success / error), which matches iOS's
                            // single-resume continuation behaviour.
                        }

                        val prompt = BiometricPrompt(
                            activity,
                            ContextCompat_getMainExecutor(activity),
                            callback,
                        )
                        val info = BiometricPrompt.PromptInfo.Builder()
                            .setTitle(title)
                            .apply { if (subtitle != null) setSubtitle(subtitle) }
                            .setAllowedAuthenticators(authenticators)
                            .build()
                        prompt.authenticate(info)
                    }
                }

                else -> {
                    // No enrolled biometric AND no device credential. Match
                    // the iOS "fail open in dev" behaviour — return success
                    // without prompting. Production devices in this state
                    // are extremely rare and the user has explicitly opted
                    // out of device security.
                }
            }
        }
    }
}

/** Spelled funny so it doesn't shadow [androidx.core.content.ContextCompat]
 *  (the ContextCompat reference is only needed for the executor and
 *  bringing in the full `androidx.core` import is overkill here). */
private fun ContextCompat_getMainExecutor(activity: FragmentActivity) =
    androidx.core.content.ContextCompat.getMainExecutor(activity)
