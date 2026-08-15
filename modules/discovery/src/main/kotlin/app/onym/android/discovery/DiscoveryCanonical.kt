package app.onym.android.discovery

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Canonical signing bytes per `Discovery-Static-Ed25519.md` §3 —
 * the system's cross-language byte-agreement precedent (moderation
 * seat's `authority/src/canonical.rs` and its byte-identical Swift
 * twin; Rust `onym-discovery` and Kotlin here pin the *same*
 * `canonical-bytes.bin` fixture):
 *
 *  1. decode the document as UTF-8 JSON; the top level must be an
 *     object;
 *  2. remove the `signature` field **structurally** (never by string
 *     surgery — planted signature copies elsewhere in the document
 *     make textual removal forgeable);
 *  3. re-serialize compactly (no insignificant whitespace) with all
 *     object keys, at every nesting level, sorted by UTF-8 byte
 *     order; `/` is not escaped.
 *
 * kotlinx `Json` fits step 3 as-is: its default output is compact,
 * its string escaping is the minimal JSON form (no `\/`), and a
 * parsed number literal re-serializes verbatim (the profile forbids
 * floats/exponents, so no normalisation ambiguity exists). `JsonObject`
 * preserves insertion order, so sorting is done by rebuilding every
 * object from a key-sorted map, recursively. The
 * `canonical-input.json` → `canonical-bytes.bin` fixture test proves
 * byte-exactness.
 */
object DiscoveryCanonical {

    /**
     * Produce the Ed25519 signing bytes for a published document.
     *
     * @throws SerializationException if [raw] is not valid JSON.
     * @throws IllegalArgumentException if the top level is not an object.
     */
    fun signingBytes(raw: ByteArray): ByteArray {
        val element = Json.parseToJsonElement(String(raw, Charsets.UTF_8))
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("canonical form requires a top-level JSON object")
        val withoutSignature = JsonObject(obj.filterKeys { it != "signature" })
        val sorted = sortKeysRecursively(withoutSignature)
        return Json.encodeToString(JsonElement.serializer(), sorted)
            .toByteArray(Charsets.UTF_8)
    }

    /** Rebuild [element] with every object's keys sorted by UTF-8
     *  byte order, at every nesting level. Arrays keep their order
     *  (order is data); primitives pass through untouched. */
    private fun sortKeysRecursively(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedWith(compareBy(UTF8_BYTE_ORDER) { it.key })
                .associate { (key, value) -> key to sortKeysRecursively(value) }
        )
        is JsonArray -> JsonArray(element.map { sortKeysRecursively(it) })
        else -> element
    }

    /**
     * UTF-8 byte order, not `String.compareTo` — the two differ for
     * strings containing surrogate pairs (UTF-16 code-unit order sorts
     * U+E000..U+FFFF *after* supplementary characters; UTF-8 byte
     * order sorts them before). Profile keys are ASCII lowercase where
     * both orders coincide, but the comparator is written to the spec,
     * not to the happy case.
     */
    private val UTF8_BYTE_ORDER = Comparator<String> { a, b ->
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        val limit = minOf(aBytes.size, bBytes.size)
        for (i in 0 until limit) {
            val diff = (aBytes[i].toInt() and 0xFF) - (bBytes[i].toInt() and 0xFF)
            if (diff != 0) return@Comparator diff
        }
        aBytes.size - bBytes.size
    }
}
