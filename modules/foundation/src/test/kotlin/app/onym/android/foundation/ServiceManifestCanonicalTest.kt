package app.onym.android.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Canonical signing bytes: structural top-level signature drop,
 * recursive UTF-8-byte-order key sort, compact output, unescaped
 * slashes — the same rules as `:discovery`'s DiscoveryCanonical,
 * implemented locally so seat consumers need no `:discovery` dep.
 */
class ServiceManifestCanonicalTest {

    private fun canonical(json: String): String =
        String(ServiceManifestCanonical.signingBytes(json.toByteArray()), Charsets.UTF_8)

    @Test
    fun sortsKeysRecursivelyAndCompacts() {
        val input = """
            {
              "zebra": {"b": 2, "a": 1},
              "alpha": [ {"y": true, "x": false}, 3 ],
              "mid": "value"
            }
        """.trimIndent()
        assertEquals(
            """{"alpha":[{"x":false,"y":true},3],"mid":"value","zebra":{"a":1,"b":2}}""",
            canonical(input),
        )
    }

    @Test
    fun dropsTopLevelSignatureStructurally() {
        val input = """{"b":1,"signature":"c2ln","a":2}"""
        assertEquals("""{"a":2,"b":1}""", canonical(input))
    }

    @Test
    fun keepsNestedSignatureKeys() {
        // Only the TOP-LEVEL signature is removed. A planted copy of
        // the signature deeper in the document stays — which is
        // exactly why removal must be structural, not string surgery:
        // textual removal of every "signature" occurrence would let
        // an attacker craft two documents with the same signing bytes.
        val input = """{"signature":"outer","nested":{"signature":"inner"}}"""
        assertEquals("""{"nested":{"signature":"inner"}}""", canonical(input))
    }

    @Test
    fun doesNotEscapeSlashes() {
        val input = """{"url":"https://courier.example.com/v1/api"}"""
        assertEquals("""{"url":"https://courier.example.com/v1/api"}""", canonical(input))
    }

    @Test
    fun arraysKeepTheirOrder() {
        // Array order is data — only object keys sort.
        val input = """{"list":["c","a","b"]}"""
        assertEquals("""{"list":["c","a","b"]}""", canonical(input))
    }

    @Test
    fun stableAcrossInputFormatting() {
        // The same document with different whitespace / key order
        // yields identical signing bytes.
        val a = canonical("""{"a":1,"b":{"c":2}}""")
        val b = canonical("{\n  \"b\": { \"c\": 2 },\n  \"a\": 1\n}")
        assertEquals(a, b)
    }

    @Test
    fun rejectsNonObjectTopLevel() {
        assertThrows(ServiceManifestException.ManifestInvalid::class.java) {
            ServiceManifestCanonical.signingBytes("[1,2]".toByteArray())
        }
    }

    @Test
    fun rejectsInvalidJson() {
        assertThrows(ServiceManifestException.ManifestInvalid::class.java) {
            ServiceManifestCanonical.signingBytes("{".toByteArray())
        }
    }
}
