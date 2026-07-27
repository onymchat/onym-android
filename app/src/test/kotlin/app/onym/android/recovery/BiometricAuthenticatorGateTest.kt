package app.onym.android.recovery

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the DEBUG-vs-RELEASE fail-open/fail-closed policy of
 * [biometricGateDecision] — the ONY-009 security fix.
 *
 * The recovery-phrase gate used to `else -> return success` for *every*
 * non-[BiometricManager.BIOMETRIC_SUCCESS] status, revealing the phrase
 * with no prompt when no credential was enrolled (fail OPEN in release).
 * Release builds must now fail CLOSED; only debug builds keep the
 * bypass so simulator/dev flows still work.
 *
 * Pure-function test (no [androidx.biometric.BiometricPrompt], no
 * `FragmentActivity`) — the `canAuthenticate` result is injected as an
 * int and the build type as a boolean.
 */
class BiometricAuthenticatorGateTest {

    /** Every non-success status `canAuthenticate` can return. */
    private val nonSuccessStatuses = intArrayOf(
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
    )

    @Test
    fun success_alwaysPrompts_regardlessOfBuildType() {
        assertEquals(
            BiometricGateDecision.PROMPT,
            biometricGateDecision(BiometricManager.BIOMETRIC_SUCCESS, isDebugBuild = false),
        )
        assertEquals(
            BiometricGateDecision.PROMPT,
            biometricGateDecision(BiometricManager.BIOMETRIC_SUCCESS, isDebugBuild = true),
        )
    }

    @Test
    fun release_failsClosed_forEveryNonSuccessStatus() {
        for (status in nonSuccessStatuses) {
            assertEquals(
                "release build must NOT authorize for status=$status",
                BiometricGateDecision.DENY,
                biometricGateDecision(status, isDebugBuild = false),
            )
        }
    }

    @Test
    fun debug_failsOpen_forEveryNonSuccessStatus() {
        for (status in nonSuccessStatuses) {
            assertEquals(
                "debug build must keep the dev bypass for status=$status",
                BiometricGateDecision.BYPASS,
                biometricGateDecision(status, isDebugBuild = true),
            )
        }
    }
}
