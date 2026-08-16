package app.onym.android.moderation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The canonical JSON form every moderation signing surface uses —
 * mandate signing bytes here, matching the backend's `canonical.rs`
 * (which parses, drops the signature field structurally, sorts keys by
 * UTF-8 byte order, and re-serializes compactly) and iOS's
 * `ModerationCanonicalEncoder` (`.sortedKeys` +
 * `.withoutEscapingSlashes` + ISO 8601 dates + omitted nils).
 *
 * kotlinx-serialization compact output never escapes `/`, and
 * `ModerationJson` omits absent optionals, so the only work here is
 * the recursive byte-order key sort.
 *
 * PROVISIONAL in the same sense as both siblings: the draft spec fixes
 * no canonical JSON form. When it does, this is the single place that
 * changes — and objects signed before then need re-signing.
 */
object ModerationCanonicalJson {
    /** `element`, keys byte-order-sorted at every level, printed
     * compactly. */
    fun canonicalBytes(element: JsonElement): ByteArray =
        Json.encodeToString(JsonElement.serializer(), sorted(element)).encodeToByteArray()

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedWith(compareBy(ByteOrderStringComparator) { it.key })
                .associate { (key, value) -> key to sorted(value) },
        )
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }

    /**
     * UTF-8 byte order, not `String.compareTo` (UTF-16 code units).
     * The two disagree above the BMP; sorting by bytes is what
     * serde_json and Swift's `.sortedKeys` both do.
     */
    private object ByteOrderStringComparator : Comparator<String> {
        override fun compare(left: String, right: String): Int {
            val a = left.encodeToByteArray()
            val b = right.encodeToByteArray()
            for (i in 0 until minOf(a.size, b.size)) {
                val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (diff != 0) return diff
            }
            return a.size - b.size
        }
    }
}
