package app.onym.android.recovery

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.onym.android.BuildConfig
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
    /** Whether fail-open is permitted when no usable authenticator is
     *  available. Defaults to [BuildConfig.DEBUG] so it's only ever
     *  true in developer builds; release builds fail CLOSED. Injectable
     *  so the gate decision can be unit-tested for both build types. */
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
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

            when (biometricGateDecision(manager.canAuthenticate(authenticators), isDebugBuild)) {
                BiometricGateDecision.PROMPT -> {
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

                BiometricGateDecision.BYPASS -> {
                    // DEBUG builds only: no enrolled biometric / no device
                    // credential / unavailable hardware. Match the iOS
                    // "fail open in dev" behaviour — return success without
                    // prompting so simulator/dev flows keep working.
                }

                BiometricGateDecision.DENY -> {
                    // RELEASE: fail CLOSED. Without a usable authenticator we
                    // cannot show a real prompt, so refuse to reveal the
                    // recovery phrase. Thrown the same way a denied/cancelled
                    // prompt is (see onAuthenticationError above) so callers
                    // handle it uniformly.
                    throw BiometricAuthException(
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        NO_DEVICE_CREDENTIAL_MESSAGE,
                    )
                }
            }
        }
    }
}

/** Outcome of the pre-prompt authenticator probe. */
internal enum class BiometricGateDecision {
    /** A usable authenticator is enrolled — show the system prompt. */
    PROMPT,

    /** DEBUG fail-open — authorize without a prompt. */
    BYPASS,

    /** RELEASE fail-closed — refuse; no usable authenticator. */
    DENY,
}

/** User-facing message for the fail-closed path — guides the user to
 *  enrol a device credential so they can reach their recovery phrase. */
internal const val NO_DEVICE_CREDENTIAL_MESSAGE =
    "Set up a screen lock (PIN, pattern, password, or biometric) in " +
        "your device settings to reveal your recovery phrase."

/**
 * Pure decision for how to gate on a [BiometricManager.canAuthenticate]
 * status. Extracted so the DEBUG/RELEASE fail-open-vs-fail-closed policy
 * is unit-testable without a real [BiometricPrompt].
 *
 * [BiometricManager.BIOMETRIC_SUCCESS] → [PROMPT][BiometricGateDecision.PROMPT].
 * Any other status (no enrolled credential, hardware unavailable,
 * security update required, unsupported) → fail OPEN
 * ([BYPASS][BiometricGateDecision.BYPASS]) only when [isDebugBuild];
 * otherwise fail CLOSED ([DENY][BiometricGateDecision.DENY]).
 */
internal fun biometricGateDecision(
    canAuthenticateStatus: Int,
    isDebugBuild: Boolean,
): BiometricGateDecision =
    when (canAuthenticateStatus) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricGateDecision.PROMPT
        else -> if (isDebugBuild) BiometricGateDecision.BYPASS else BiometricGateDecision.DENY
    }

/** Spelled funny so it doesn't shadow [androidx.core.content.ContextCompat]
 *  (the ContextCompat reference is only needed for the executor and
 *  bringing in the full `androidx.core` import is overkill here). */
private fun ContextCompat_getMainExecutor(activity: FragmentActivity) =
    androidx.core.content.ContextCompat.getMainExecutor(activity)
