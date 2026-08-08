package app.onym.android.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format pin for [IntroCapability]. The shape is the contract
 * onym-ios will mirror — keep it stable.
 */
class IntroCapabilityTest {

    private val sampleIntroPub = ByteArray(32) { 0xAA.toByte() }
    private val sampleGroupId = ByteArray(32) { 0x42 }

    @Test
    fun roundtrip_minimalShape_preservesAllFields() {
        val original = IntroCapability(
            introPublicKey = sampleIntroPub,
            groupId = sampleGroupId,
            groupName = null,
        )
        val encoded = original.encode()
        val decoded = IntroCapability.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun roundtrip_withGroupName_preservesAllFields() {
        val original = IntroCapability(
            introPublicKey = sampleIntroPub,
            groupId = sampleGroupId,
            groupName = "Family",
        )
        val encoded = original.encode()
        val decoded = IntroCapability.decode(encoded)
        assertEquals(original, decoded)
        assertEquals("Family", decoded.groupName)
    }

    @Test
    fun encode_isUrlSafeBase64_noPaddingOrSpecialChars() {
        val cap = IntroCapability(sampleIntroPub, sampleGroupId, "test")
        val encoded = cap.encode()
        // URL-safe base64 swaps `+`/`/` for `-`/`_` and drops `=` padding;
        // the result drops into a query string without percent-encoding.
        assertFalse("no `+` in URL-safe encoding", encoded.contains('+'))
        assertFalse("no `/` in URL-safe encoding", encoded.contains('/'))
        assertFalse("no `=` padding", encoded.contains('='))
        assertFalse("no whitespace", encoded.any { it.isWhitespace() })
    }

    @Test
    fun toAppLink_isOnymChatJoinPath() {
        val cap = IntroCapability(sampleIntroPub, sampleGroupId)
        val link = cap.toAppLink()
        assertTrue("expected onym.app host", link.startsWith("https://onym.app/join?c="))
    }

    @Test
    fun toCustomSchemeLink_isOnymJoinScheme() {
        val cap = IntroCapability(sampleIntroPub, sampleGroupId)
        val link = cap.toCustomSchemeLink()
        assertTrue("expected onym:// scheme", link.startsWith("onym://join?c="))
    }

    @Test
    fun fromLink_appLinkForm_decodesBackToOriginal() {
        val original = IntroCapability(sampleIntroPub, sampleGroupId, "Crew")
        val parsed = IntroCapability.fromLink(original.toAppLink())
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun fromLink_customSchemeForm_decodesBackToOriginal() {
        val original = IntroCapability(sampleIntroPub, sampleGroupId, "Crew")
        val parsed = IntroCapability.fromLink(original.toCustomSchemeLink())
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun fromLink_extraQueryParams_returnsCapability_ignoringExtras() {
        // Future schemas may grow tracking params (utm_*, etc.) — the
        // parser must pluck `c=` and ignore the rest.
        val cap = IntroCapability(sampleIntroPub, sampleGroupId, "X")
        val link = "${cap.toAppLink()}&utm_source=share-sheet&ref=foo"
        val parsed = IntroCapability.fromLink(link)
        assertNotNull(parsed)
        assertEquals(cap, parsed)
    }

    @Test
    fun fromLink_missingCapabilityParam_returnsNull() {
        assertNull(IntroCapability.fromLink("https://onym.app/join"))
        assertNull(IntroCapability.fromLink("https://onym.app/join?other=value"))
    }

    @Test
    fun fromLink_malformedUri_returnsNull() {
        assertNull(IntroCapability.fromLink("not a url"))
        assertNull(IntroCapability.fromLink(""))
    }

    @Test
    fun decode_invalidBase64_throwsInvalidIntroCapability() {
        val thrown = assertThrows(InvalidIntroCapability::class.java) {
            IntroCapability.decode("@@@not-base64@@@")
        }
        assertNotNull(thrown.message)
    }

    @Test
    fun decode_validBase64_butNotJson_throwsInvalidIntroCapability() {
        val notJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not json at all".toByteArray())
        assertThrows(InvalidIntroCapability::class.java) {
            IntroCapability.decode(notJson)
        }
    }

    @Test
    fun decode_validJson_butWrongPubkeySize_throwsInvalidIntroCapability() {
        val badShape = """{"intro_pub":"AAA=","group_id":"AAA="}"""
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(badShape.toByteArray())
        assertThrows(InvalidIntroCapability::class.java) {
            IntroCapability.decode(encoded)
        }
    }

    @Test
    fun constructor_rejectsWrongSizedKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            IntroCapability(introPublicKey = ByteArray(31), groupId = sampleGroupId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntroCapability(introPublicKey = sampleIntroPub, groupId = ByteArray(33))
        }
    }

    @Test
    fun shareText_includesGroupName_whenSet() {
        val cap = IntroCapability(sampleIntroPub, sampleGroupId, "Friends")
        val text = IntroCapability.shareText(cap.toAppLink(), cap.groupName)
        assertTrue(text.contains("\"Friends\""))
        assertTrue(text.contains("https://onym.app/join?c="))
    }

    @Test
    fun shareText_omitsGroupName_whenBlankOrNull() {
        val cap = IntroCapability(sampleIntroPub, sampleGroupId)
        val text = IntroCapability.shareText(cap.toAppLink(), cap.groupName)
        assertFalse(text.contains("\""))  // no quoted name
        assertTrue(text.contains("Join my chat"))
    }

    @Test
    fun roundtrip_byteForByteIntroPub_isPreserved() {
        // Random-ish bytes — make sure base64+JSON+base64 doesn't
        // mutate any byte position.
        val pub = ByteArray(32) { (it * 7 + 13).toByte() }
        val gid = ByteArray(32) { (it * 11 + 3).toByte() }
        val cap = IntroCapability(pub, gid, "X")
        val decoded = IntroCapability.decode(cap.encode())
        assertArrayEquals(pub, decoded.introPublicKey)
        assertArrayEquals(gid, decoded.groupId)
    }
}
