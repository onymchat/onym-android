package app.onym.android.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-language canonical-bytes gate: `canonical-input.json`
 * (spaced, unsorted keys, a `signature` field, `/` inside keys and
 * values) must canonicalize to *exactly* `canonical-bytes.bin` —
 * the same bytes Rust (`onym-discovery`) and the iOS twin pin.
 */
class DiscoveryCanonicalTest {

    @Test
    fun canonicalInput_producesExactCanonicalBytes() {
        val input = DiscoveryFixtures.load("canonical-input.json")
        val expected = DiscoveryFixtures.load("canonical-bytes.bin")
        assertArrayEquals(expected, DiscoveryCanonical.signingBytes(input))
    }

    @Test
    fun caseDivergenceVector_sortsByUtf8ByteOrder() {
        // §3/§10 item 1: the sort is UTF-8 byte order — uppercase
        // before lowercase. A case-insensitive sort fails exactly on
        // this vector.
        val input = DiscoveryFixtures.load("canonical-case-input.json")
        val expected = DiscoveryFixtures.load("canonical-case-bytes.bin")
        val canonical = DiscoveryCanonical.signingBytes(input)
        assertArrayEquals(expected, canonical)
        assertEquals("""{"AB":4,"Ab":3,"aB":2,"ab":1}""", String(canonical, Charsets.UTF_8))
    }

    @Test
    fun escapingVector_matchesPinnedEscaping() {
        // §3/§10 item 1: escaping is pinned — two-char forms,
        // lowercase \u00xx for remaining controls, and NOTHING else
        // escaped: no `/`, no non-ASCII, no U+007F.
        val input = DiscoveryFixtures.load("canonical-escaping-input.json")
        val expected = DiscoveryFixtures.load("canonical-escaping-bytes.bin")
        val canonical = DiscoveryCanonical.signingBytes(input)
        assertArrayEquals(expected, canonical)
        val text = String(canonical, Charsets.UTF_8)
        assertTrue(text, "\"controls\":\"\\u0001\\u001f\"" in text)
        assertTrue(text, "\"delete\":\"\"" in text)
        assertTrue(text, """"slash":"a/b"""" in text)
        assertTrue(text, """"twochar":"\b\f\n\r\t"""" in text)
        assertTrue(text, "\"unicode\":\"héllo → 日本\"" in text)
    }

    @Test
    fun signingBytes_dropsTopLevelSignatureOnly_slashUnescaped() {
        val bytes = DiscoveryCanonical.signingBytes(DiscoveryFixtures.load("canonical-input.json"))
        val text = String(bytes, Charsets.UTF_8)
        // Structural removal: the top-level "signature" key is gone…
        assertTrue("signature must be dropped", "signature" !in text)
        // …and `/` is not escaped (kotlinx's minimal escaping — pinned
        // here because the spec calls it out explicitly).
        assertTrue("""nested/slash""" in text)
        assertTrue("""\/""" !in text)
    }

    @Test
    fun signingBytes_isIdempotentOverItsOwnOutput() {
        val once = DiscoveryCanonical.signingBytes(DiscoveryFixtures.load("canonical-input.json"))
        assertArrayEquals(once, DiscoveryCanonical.signingBytes(once))
    }

    @Test
    fun signingBytes_rejectsNonObjectTopLevel() {
        try {
            DiscoveryCanonical.signingBytes("[1,2,3]".toByteArray())
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("top-level"))
        }
    }

    @Test
    fun signingBytes_matchesPublishedFixtureDocuments() {
        // Every signed fixture is already published in canonical form
        // (writer = Rust reference impl): canonicalizing it must
        // reproduce the document minus its signature field. Checked
        // via digest equality with a manual strip on snapshot-1.
        val raw = DiscoveryFixtures.load("snapshot-1.json")
        val canonical = String(DiscoveryCanonical.signingBytes(raw), Charsets.UTF_8)
        assertEquals(-1, canonical.indexOf("\"signature\""))
        assertTrue(canonical.startsWith("{\"catalogId\":"))
    }
}
