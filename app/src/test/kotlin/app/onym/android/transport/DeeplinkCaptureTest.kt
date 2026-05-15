package app.onym.android.transport

import app.onym.android.group.IntroCapability
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [DeeplinkCapture.introCapabilityFromUri]. The
 * `Intent` overload is one line of glue and trusted to delegate
 * correctly; the host/scheme allowlist + `IntroCapability.fromLink`
 * call live here and are exercised via the `String?` form.
 *
 * Pin the allowlist semantics:
 *  - `https://onym.app/join?c=…` → decoded
 *  - `onym://join?c=…` → decoded
 *  - any other scheme/host → null (the manifest is the primary gate;
 *    this is defense in depth)
 *  - malformed payload → null (`fromLink`'s thrown
 *    `InvalidIntroCapability` is swallowed; the activity should
 *    no-op rather than crash on a dud intent)
 */
class DeeplinkCaptureTest {

    private val introPub = ByteArray(32) { it.toByte() }
    private val groupId = ByteArray(32) { (0x40 + it).toByte() }

    private fun fixture(name: String? = null): IntroCapability =
        IntroCapability(introPub, groupId, name)

    @Test
    fun universalLink_decodes() {
        val link = fixture(name = "Test").toAppLink()
        val decoded = DeeplinkCapture.introCapabilityFromUri(link)
        assertNotNull(decoded)
        assertArrayEquals(introPub, decoded!!.introPublicKey)
        assertArrayEquals(groupId, decoded.groupId)
        assertEquals("Test", decoded.groupName)
    }

    @Test
    fun customScheme_decodes() {
        val link = fixture().toCustomSchemeLink()
        val decoded = DeeplinkCapture.introCapabilityFromUri(link)
        assertNotNull(decoded)
        assertArrayEquals(introPub, decoded!!.introPublicKey)
        assertNull(decoded.groupName)
    }

    @Test
    fun foreignHost_returnsNull() {
        // Same path + valid `c=` payload but the host isn't ours.
        // A malicious app could construct this and ACTION_VIEW it
        // at us if our intent-filter were too loose; the in-code
        // allowlist makes the boundary explicit.
        val good = fixture().encode()
        val link = "https://example.com/join?c=$good"
        assertNull(DeeplinkCapture.introCapabilityFromUri(link))
    }

    @Test
    fun foreignScheme_returnsNull() {
        val good = fixture().encode()
        val link = "ftp://onym.app/join?c=$good"
        assertNull(DeeplinkCapture.introCapabilityFromUri(link))
    }

    @Test
    fun nullOrEmpty_returnsNull() {
        assertNull(DeeplinkCapture.introCapabilityFromUri(null))
        assertNull(DeeplinkCapture.introCapabilityFromUri(""))
    }

    @Test
    fun malformedUri_returnsNull() {
        assertNull(DeeplinkCapture.introCapabilityFromUri("not a uri"))
    }

    @Test
    fun knownHostMissingC_returnsNull() {
        assertNull(DeeplinkCapture.introCapabilityFromUri("https://onym.app/join"))
        assertNull(DeeplinkCapture.introCapabilityFromUri("onym://join"))
    }

    @Test
    fun knownHostBadCPayload_returnsNull() {
        // Allowed scheme/host, but the `c` value is not valid base64 +
        // the JSON payload doesn't decode. `fromLink` throws
        // InvalidIntroCapability; we swallow → null so the activity
        // doesn't crash on a bad share.
        assertNull(DeeplinkCapture.introCapabilityFromUri("https://onym.app/join?c=not-base64!!!"))
    }

    @Test
    fun caseInsensitiveSchemeAndHost_decodes() {
        // RFC 3986: scheme + host are case-insensitive. `Uri.parse`
        // lowercases the scheme but preserves host case; our
        // allowlist normalizes both. Build a deliberately mixed-case
        // form to pin the behavior.
        val good = fixture().encode()
        val link = "HTTPS://Onym.App/join?c=$good"
        assertNotNull(DeeplinkCapture.introCapabilityFromUri(link))
    }
}
