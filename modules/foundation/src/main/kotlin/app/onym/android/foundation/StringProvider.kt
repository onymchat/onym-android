package app.onym.android.foundation

import android.content.Context
import androidx.annotation.StringRes

/**
 * Minimal seam over [Context.getString] so code that isn't a
 * `@Composable` — a background coroutine building disclosure text, a
 * schedule computing its own sentence, a repository resolving an
 * error message — can resolve localized strings without holding a
 * [Context] or requiring Compose's composition context.
 *
 * Mirrors `app.onym.android.recovery.StringProvider` (the app-module
 * original this seam was extracted from) — same shape, moved here so
 * `:backup`/`:backup-ui` and any future non-UI-module caller can use
 * it without depending on the app module. Tests provide a fake that
 * returns a resource name (or a formatted placeholder) so assertions
 * stay locale-independent.
 */
interface StringProvider {
    /** Resolve a plain string resource. */
    operator fun get(@StringRes resId: Int): String

    /** Resolve a formatted string resource (`String.format`-style
     *  placeholders, e.g. `%1$d`, `%1$s`). */
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String

    /** Resolve a `<plurals>` resource for [quantity], matching
     *  `Resources.getQuantityString`'s contract: [quantity] both
     *  selects the grammatical form AND is available as `%1$d` if the
     *  template uses it — [formatArgs], if given, fill any FURTHER
     *  placeholders beyond that first one. */
    fun getQuantity(@androidx.annotation.PluralsRes pluralsResId: Int, quantity: Int, vararg formatArgs: Any): String
}

/**
 * Production [StringProvider] backed by an Android [Context]. Use the
 * application context (not an Activity) so we don't pin an Activity
 * past configuration change — the localized resource set is the same
 * either way.
 */
class AndroidStringProvider(private val context: Context) : StringProvider {
    override fun get(resId: Int): String = context.getString(resId)
    override fun get(resId: Int, vararg formatArgs: Any): String = context.getString(resId, *formatArgs)
    override fun getQuantity(pluralsResId: Int, quantity: Int, vararg formatArgs: Any): String {
        val allArgs = (listOf<Any>(quantity) + formatArgs.toList()).toTypedArray()
        return context.resources.getQuantityString(pluralsResId, quantity, *allArgs)
    }
}
