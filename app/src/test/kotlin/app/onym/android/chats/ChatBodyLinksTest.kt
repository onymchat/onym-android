package app.onym.android.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact ranges and URLs the bubble styles as tappable. Ranges
 * matter as much as detection: a wrong range styles half a word as a
 * link, and an over-eager pattern turns prose into links.
 */
class ChatBodyLinksTest {

    @Test
    fun `plain prose detects nothing`() {
        assertTrue(ChatBodyLinks.detect("just words, no links here.").isEmpty())
        // Bare domains without a marker stay prose — the cost of
        // linking "onym.app" is matching every "file.txt".
        assertTrue(ChatBodyLinks.detect("see onym.app or file.txt").isEmpty())
        // Emails are not web links.
        assertTrue(ChatBodyLinks.detect("write to lead@onym.app").isEmpty())
    }

    @Test
    fun `https link is detected with its exact range`() {
        val body = "docs at https://onym.app/help ok?"
        val link = ChatBodyLinks.detect(body).single()
        assertEquals("https://onym.app/help", body.substring(link.range))
        assertEquals("https://onym.app/help", link.url)
    }

    @Test
    fun `www link opens as https`() {
        val body = "try www.example.com today"
        val link = ChatBodyLinks.detect(body).single()
        assertEquals("www.example.com", body.substring(link.range))
        assertEquals("https://www.example.com", link.url)
    }

    @Test
    fun `sentence punctuation is not part of the URL`() {
        for ((body, expected) in listOf(
            "read https://onym.app/doc." to "https://onym.app/doc",
            "really https://onym.app/doc?!" to "https://onym.app/doc",
            "see https://onym.app/a, then https://onym.app/b;" to "https://onym.app/a",
            "«https://onym.app/q»" to "https://onym.app/q",
        )) {
            val link = ChatBodyLinks.detect(body).first()
            assertEquals(body, expected, link.url)
        }
    }

    /** Wikipedia-style paths keep their balanced paren; a wrapping
     * paren is punctuation. */
    @Test
    fun `closing parens are kept only when the URL opened them`() {
        val wrapped = ChatBodyLinks.detect("(see https://onym.app/x)").single()
        assertEquals("https://onym.app/x", wrapped.url)

        val wiki = ChatBodyLinks.detect(
            "https://en.wikipedia.org/wiki/Onym_(app)",
        ).single()
        assertEquals("https://en.wikipedia.org/wiki/Onym_(app)", wiki.url)
    }

    @Test
    fun `multiple links keep order and query strings survive`() {
        val body = "a https://x.example/p?q=1&r=2 b http://y.example c"
        val links = ChatBodyLinks.detect(body)
        assertEquals(
            listOf("https://x.example/p?q=1&r=2", "http://y.example"),
            links.map { it.url },
        )
        for (link in links) {
            assertEquals(link.url.removePrefix("https://").removePrefix("http://"),
                body.substring(link.range).removePrefix("https://").removePrefix("http://"))
        }
    }

    /** No custom schemes: a chat message must not become an intent
     * launcher. */
    @Test
    fun `non-web schemes are not linked`() {
        assertTrue(ChatBodyLinks.detect("call tel:123 or ftp://x.example/f").isEmpty())
        assertTrue(ChatBodyLinks.detect("intent://evil#Intent;end").isEmpty())
        // The review's escape: a `www.` INSIDE a non-web scheme's
        // authority must not become a link of its own.
        assertTrue(ChatBodyLinks.detect("intent://www.evil.com/x#Intent;end").isEmpty())
    }

    @Test
    fun `www inside an email stays prose`() {
        assertTrue(ChatBodyLinks.detect("mail bob@www.example.com today").isEmpty())
    }

    /** Display keeps the author's spelling; the URL lowercases the
     * SCHEME only — Android resolves intent filters case-sensitively
     * against lowercase schemes, so an upper-case scheme is a
     * tappable link that opens nothing. Host case survives (hosts
     * are case-insensitive; paths are not, so nothing else moves). */
    @Test
    fun `mixed-case schemes normalize to a launchable url`() {
        val body = "see HtTpS://Onym.App/Doc now"
        val link = ChatBodyLinks.detect(body).single()
        assertEquals("HtTpS://Onym.App/Doc", body.substring(link.range))
        assertEquals("https://Onym.App/Doc", link.url)
    }

    @Test
    fun `a bare marker with no host is prose`() {
        assertTrue(ChatBodyLinks.detect("https:// is a prefix and www. a habit").isEmpty())
        assertTrue(ChatBodyLinks.detect("http://nohostdot").isEmpty())
        // The guard is ignoreCase like the pattern: a lone "WWW."
        // must not linkify to a dead https://WWW.
        assertTrue(ChatBodyLinks.detect("shouting WWW. at clouds").isEmpty())
    }
}
