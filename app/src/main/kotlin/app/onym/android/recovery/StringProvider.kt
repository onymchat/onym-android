package app.onym.android.recovery

import android.content.Context
import androidx.annotation.StringRes

/**
 * Minimal seam over [Context.getString] so the [RecoveryPhraseBackupViewModel]
 * can resolve localized strings without holding a [Context].
 *
 * The ViewModel needs two strings that the view layer can't supply:
 *
 *   - the prompt title shown by `BiometricPrompt` (must be passed at
 *     the moment the prompt is invoked, deep inside an `authenticate()`
 *     coroutine)
 *   - the localized error message when no recovery phrase is available
 *     (resolved at the moment of failure, no Composable around)
 *
 * Symmetric with iOS's `String(localized:)` wrapper. Tests provide a
 * fake that returns the resource name itself, so assertions stay
 * locale-independent.
 */
interface StringProvider {
    /** Resolve a plain string resource. */
    operator fun get(@StringRes resId: Int): String
}

/**
 * Production [StringProvider] backed by an Android [Context]. Use the
 * application context (not an Activity) so we don't pin an Activity
 * past configuration change — the localized resource set is the same
 * either way.
 */
class AndroidStringProvider(private val context: Context) : StringProvider {
    override fun get(resId: Int): String = context.getString(resId)
}
