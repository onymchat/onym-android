package chat.onym.android.recovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Minimal seam over [ClipboardManager] so the flow's clipboard
 * side effect can be observed in tests without writing to the real
 * system clipboard.
 *
 * Mirrors the iOS [`PasteboardWriter` protocol
 * ](https://github.com/onymchat/onym-ios/blob/main/Sources/OnymIOS/Recovery/RecoveryPhraseBackupFlow.swift)
 * 1:1.
 */
interface ClipboardWriter {
    /** Write the value to the system clipboard. */
    fun write(value: String)

    /**
     * Clear the clipboard ONLY if its current content is still [value].
     * No-op if the user has since copied something else — we don't
     * want to wipe an unrelated value the user is mid-paste.
     */
    fun clearIfStill(value: String)
}

class AndroidClipboardWriter(private val context: Context) : ClipboardWriter {

    /** Lazy so test code in modules without an Android context can
     *  type-check against this class without crashing. */
    private val clipboard: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun write(value: String) {
        val clip = ClipData.newPlainText(LABEL, value)
        // Android 13+ honours the IS_SENSITIVE flag — the system
        // clipboard preview that pops up after a paste-able copy
        // shows a redacted "Sensitive" placeholder instead of the
        // actual recovery phrase. Older Androids ignore the flag,
        // which is OK — the surface is the same as iOS pre-15
        // (no in-OS preview at all).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    override fun clearIfStill(value: String) {
        val current = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
        if (current == value) {
            // setPrimaryClip(null) isn't allowed; the official "clear"
            // path is `clearPrimaryClip()` on API 28+, otherwise
            // overwrite with an empty plaintext entry.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, ""))
            }
        }
    }

    companion object {
        /** The clipboard label is user-visible in some launchers'
         *  paste UI — keep it generic. */
        private const val LABEL = "recovery_phrase"
    }
}
