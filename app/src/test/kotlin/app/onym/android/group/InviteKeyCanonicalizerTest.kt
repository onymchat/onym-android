package app.onym.android.group

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * Pins [canonicalizeInviteKey] against the link shapes a scan can
 * yield. The contract is the twin of onym-ios
 * `CreateGroupFlow.canonicalizeInviteKey(_:)` — keep them in lockstep.
 */
class InviteKeyCanonicalizerTest {

    // A fixed 32-byte key + its hex and URL-safe-base64 encodings.
    private val keyBytes = ByteArray(32) { it.toByte() }
    private val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
    private val keyB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)

    @Test
    fun bareHex_returnedUnchanged() {
        assertEquals(keyHex, canonicalizeInviteKey(keyHex))
    }

    @Test
    fun bareHex_trimmedButCasePreserved() {
        // Matches iOS: only `payload=` is lowercased; a bare hex paste
        // is returned as-is (decodeHex accepts either case).
        val upper = keyHex.uppercase()
        assertEquals(upper, canonicalizeInviteKey("  $upper  "))
    }

    @Test
    fun identityInviteLink_extractsHexFromK() {
        val link = "https://onym.app/i?k=$keyB64Url"
        assertEquals(keyHex, canonicalizeInviteKey(link))
    }

    @Test
    fun identityInviteLink_customScheme_extractsHexFromK() {
        // Same `k` param under a non-https URI still parses.
        val link = "onym://i?k=$keyB64Url"
        assertEquals(keyHex, canonicalizeInviteKey(link))
    }

    @Test
    fun legacyIosSettingsLink_extractsLoweredPayload() {
        val link = "https://onym.app?payload=${keyHex.uppercase()}"
        assertEquals(keyHex, canonicalizeInviteKey(link))
    }

    @Test
    fun unrecognisedString_returnedTrimmed() {
        assertEquals("not-a-key", canonicalizeInviteKey("  not-a-key  "))
    }

    @Test
    fun linkWithoutKnownParams_returnedTrimmed() {
        val link = "https://onym.app/i?foo=bar"
        assertEquals(link, canonicalizeInviteKey(link))
    }

    @Test
    fun garbledBase64InK_fallsThroughToTrimmedInput() {
        // '!' isn't a base64 char — decode fails, so the raw link is
        // returned and the caller's hex validation rejects it.
        val link = "https://onym.app/i?k=!!!notbase64!!!"
        assertEquals(link, canonicalizeInviteKey(link))
    }
}
