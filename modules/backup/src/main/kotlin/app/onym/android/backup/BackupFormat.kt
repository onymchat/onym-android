package app.onym.android.backup

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.OffsetDateTime

/**
 * Identifier / digest / timestamp syntax shared across the backup
 * domain — the string forms `SnapshotReference`, `BackupTerms`,
 * `ErasureReceipt`, and the wire adapter all agree on.
 *
 * Mirrors `BackupFormat` in onym-ios OnymBackup.
 */
object BackupFormat {

    /** `sha256:` + 64 lowercase hex over [data]. */
    fun digest(data: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(data).toLowercaseHex()

    /** `sha256:<64 lowercase hex>` syntax check. Strict ASCII —
     *  deliberately does not use [Char.isDigit], which accepts
     *  Unicode digit look-alikes (e.g. Arabic-Indic `٣`) that would
     *  pass a locale-aware digit check but are not valid hex. */
    fun isDigest(value: String): Boolean {
        if (!value.startsWith("sha256:")) return false
        val hex = value.removePrefix("sha256:")
        return hex.length == 64 && isLowercaseHex(hex)
    }

    fun isLowercaseHex(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun ByteArray.toLowercaseHex(): String = joinToString("") { "%02x".format(it) }

    fun bytesFromLowercaseHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || !isLowercaseHex(hex)) return null
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }

    /** RFC 3339, UTC, second precision, no fractional seconds — matches
     *  iOS `ISO8601DateFormatter(.withInternetDateTime)`. */
    fun rfc3339(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    /** Accepts second- or fractional-second precision, `Z` or a
     *  numeric offset (lenient parse; [rfc3339] is what this module
     *  writes). */
    fun instantFromRfc3339(value: String): Instant? = try {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}
