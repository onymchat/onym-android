package app.onym.android.group

import java.net.URI
import java.util.Base64

/**
 * Pull a candidate inbox key out of a raw scanned/pasted string.
 *
 * The QR scanner ([app.onym.android.scan.QrScannerScreen]) is
 * deliberately generic — it hands back whatever text the code
 * encodes. This function turns that text into the bare 64-char hex
 * inbox key the Create Group "Invite by inbox key" field expects, so
 * the user sees a reviewable key before tapping "Add invitee" and the
 * existing validation in [CreateGroupViewModel.tappedAddInvitee]
 * surfaces the same inline error on a bad scan as on a bad paste.
 *
 * Recognises:
 *  - bare hex — returned trimmed, case preserved (the recipient's
 *    "Settings → Identity → Inbox Key" string); the caller's
 *    [decodeHex] accepts either case.
 *  - `https://onym.app/i?k=<urlsafe-base64>` — the identity-invite
 *    QR rendered by [app.onym.android.identity.inviteUrl] /
 *    `OnymQrCode`; `k` is the 32-byte X25519 inbox key in URL-safe
 *    Base64 (no padding).
 *  - `https://onym.app?payload=<hex>` — the legacy onym-ios settings
 *    QR (kept so a key shared from an older iOS build still scans).
 *
 * Falls back to the trimmed input on anything it doesn't recognise so
 * the caller's length/hex validation produces a meaningful message.
 *
 * Mirrors `CreateGroupFlow.canonicalizeInviteKey(_:)` from onym-ios.
 * Pure (java.net.URI + java.util.Base64, no `android.net.Uri`) so it
 * unit-tests on the JVM without Robolectric.
 */
fun canonicalizeInviteKey(raw: String): String {
    val trimmed = raw.trim()
    val query = try {
        URI(trimmed).rawQuery
    } catch (_: Throwable) {
        null
    } ?: return trimmed

    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        if (eq < 0) continue
        val name = part.substring(0, eq)
        val value = part.substring(eq + 1)
        when (name) {
            // Android identity invite — 32-byte key as URL-safe base64.
            "k" -> {
                val bytes = try {
                    Base64.getUrlDecoder().decode(value)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (bytes.isNotEmpty()) return bytes.toHexLower()
            }
            // Legacy onym-ios settings QR — already hex.
            "payload" -> if (value.isNotEmpty()) return value.lowercase()
        }
    }
    return trimmed
}

private fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private const val HEX = "0123456789abcdef"
