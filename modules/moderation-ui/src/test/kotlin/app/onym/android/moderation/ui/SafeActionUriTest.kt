package app.onym.android.moderation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every backend-supplied field BannedScreen can hand to ACTION_VIEW
 * passes this allowlist — a non-http scheme in a served value would
 * otherwise become an arbitrary implicit intent from a screen that is
 * otherwise fully locked down. */
class SafeActionUriTest {
    @Test
    fun `http https mailto and bare emails pass`() {
        assertEquals("https://a.org/appeal", safeActionUri("https://a.org/appeal"))
        assertEquals("http://a.org", safeActionUri(" http://a.org "))
        assertEquals("mailto:appeals@a.org", safeActionUri("mailto:appeals@a.org"))
        assertEquals("mailto:appeals@a.org", safeActionUri("appeals@a.org"))
    }

    @Test
    fun `other schemes and free text are refused`() {
        assertNull(safeActionUri("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(safeActionUri("tel:+1555000"))
        assertNull(safeActionUri("file:///etc/hosts"))
        assertNull(safeActionUri("javascript:alert(1)"))
        assertNull(safeActionUri("the authority named in your notice"))
        assertNull(safeActionUri("content://com.android.providers/root"))
    }
}
