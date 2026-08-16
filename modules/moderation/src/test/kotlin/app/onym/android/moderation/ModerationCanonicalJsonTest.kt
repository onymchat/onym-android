package app.onym.android.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModerationCanonicalJsonTest {
    private val mandate = ModerationMandate(
        user = "onym:key:aabb",
        interface_ = "onym:component:onym-android",
        authority = "onym:component:test-authority",
        manifestHash = "0".repeat(64),
        classes = listOf("csam"),
        deviceBinding = "enrollment:fixture",
        acceptedAt = "2026-08-08T12:00:00Z",
        signatures = listOf("dXNlci1zaWc="),
    )

    /**
     * The canonical mandate form, pinned: keys byte-order sorted at
     * every level, compact, slashes unescaped, `signatures` dropped
     * structurally. The backend re-derives exactly this from the wire
     * bytes (`canonical.rs`), so the user signature verifies there iff
     * these bytes agree.
     */
    @Test
    fun `mandate signing bytes are canonical and signature-free`() {
        val expected = """{"acceptedAt":"2026-08-08T12:00:00Z",""" +
            """"authority":"onym:component:test-authority",""" +
            """"classes":["csam"],""" +
            """"deviceBinding":"enrollment:fixture",""" +
            """"interface":"onym:component:onym-android",""" +
            """"mandateVersion":1,""" +
            """"manifestHash":"${"0".repeat(64)}",""" +
            """"user":"onym:key:aabb"}"""
        assertEquals(expected, mandate.signingBytes().decodeToString())
    }

    /** Signature-independent, so the reference is stable across
     * countersigning. */
    @Test
    fun `mandate hash ignores signatures`() {
        val countersigned = mandate.copy(signatures = mandate.signatures + "aWZhY2Utc2ln")
        assertEquals(mandate.mandateHash(), countersigned.mandateHash())
        assertEquals(64, mandate.mandateHash().length)
    }

    @Test
    fun `wire bytes carry the interface spelling and the signatures`() {
        val wire = mandate.wireBytes().decodeToString()
        assert(wire.contains("\"interface\":\"onym:component:onym-android\"")) { wire }
        assert(wire.contains("\"signatures\":[\"dXNlci1zaWc=\"]")) { wire }
        assertFalse(wire.contains("interface_"))
        assertFalse(wire.contains("interface1"))
    }

    /** Slashes must survive unescaped — the backend parses and
     * re-serializes, which would break a `\/`-signed form. */
    @Test
    fun `canonical form does not escape slashes`() {
        val bytes = mandate.copy(deviceBinding = "enrollment/with/slashes").signingBytes()
        assert(bytes.decodeToString().contains("enrollment/with/slashes"))
    }

    /** UTF-8 byte order: uppercase sorts before lowercase. */
    @Test
    fun `keys sort by utf8 bytes not case-insensitively`() {
        val element = ModerationJson.json.parseToJsonElement("""{"b":1,"A":2,"a":3}""")
        assertEquals(
            """{"A":2,"a":3,"b":1}""",
            ModerationCanonicalJson.canonicalBytes(element).decodeToString(),
        )
    }
}
