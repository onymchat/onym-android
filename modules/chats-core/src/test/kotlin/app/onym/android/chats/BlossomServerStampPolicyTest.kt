package app.onym.android.chats

import app.onym.android.transport.blossom.BlobDescriptor
import app.onym.android.transport.blossom.BlossomClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [BlossomServerStampPolicy] — the trust boundary between a peer's
 * `server` stamp and the user's network. Mirrors the policy half of
 * onym-ios `ChatMediaLoaderServerBindingTests.swift`.
 */
class BlossomServerStampPolicyTest {

    /** Live client whose [bound] records the pin and returns a marker
     *  client, so tests can tell "honored" from "fell back to live". */
    private class FakeLiveClient : BlossomClient {
        val boundCalls = mutableListOf<String>()
        val boundResult = object : BlossomClient {
            override suspend fun upload(blob: ByteArray, mimeType: String) =
                BlobDescriptor("00", "", 0)
            override suspend fun download(sha256: String) = ByteArray(0)
        }

        override suspend fun upload(blob: ByteArray, mimeType: String) = BlobDescriptor("00", "", 0)
        override suspend fun download(sha256: String) = ByteArray(0)
        override fun bound(serverUrl: String): BlossomClient {
            boundCalls.add(serverUrl)
            return boundResult
        }
    }

    private val live = FakeLiveClient()

    @Test
    fun stampInAllowedSet_isHonored() {
        val client = BlossomServerStampPolicy.client(
            stamp = "https://mine.example",
            allowedServers = listOf("https://mine.example"),
            live = live,
        )
        assertSame(live.boundResult, client)
        assertEquals(listOf("https://mine.example"), live.boundCalls)
    }

    @Test
    fun stampOutsideAllowedSet_fallsBackToLive() {
        // The tracking-leak case: a hostile sender stamps a host it
        // controls. The recipient never contacts it.
        val client = BlossomServerStampPolicy.client(
            stamp = "https://evil.example",
            allowedServers = listOf("https://mine.example"),
            live = live,
        )
        assertSame(live, client)
        assertEquals(emptyList<String>(), live.boundCalls)
    }

    @Test
    fun emptyAllowlist_isTheSecureDefault_stampIgnored() {
        val client = BlossomServerStampPolicy.client(
            stamp = "https://mine.example",
            allowedServers = emptyList(),
            live = live,
        )
        assertSame(live, client)
    }

    @Test
    fun nullStamp_legacyRow_usesLive() {
        val client = BlossomServerStampPolicy.client(
            stamp = null,
            allowedServers = listOf("https://mine.example"),
            live = live,
        )
        assertSame(live, client)
    }

    @Test
    fun originComparison_normalizesCaseAndIgnoresPath() {
        val client = BlossomServerStampPolicy.client(
            stamp = "HTTPS://Mine.Example/some/path",
            allowedServers = listOf("https://mine.example"),
            live = live,
        )
        assertSame(live.boundResult, client)
    }

    @Test
    fun originComparison_distinguishesSchemeAndPort() {
        // http stamp vs https allowlist entry: different origin.
        assertSame(
            live,
            BlossomServerStampPolicy.client(
                stamp = "http://mine.example",
                allowedServers = listOf("https://mine.example"),
                live = live,
            ),
        )
        // Explicit port vs none: different origin.
        assertSame(
            live,
            BlossomServerStampPolicy.client(
                stamp = "https://mine.example:8443",
                allowedServers = listOf("https://mine.example"),
                live = live,
            ),
        )
        // Same explicit port matches.
        assertSame(
            live.boundResult,
            BlossomServerStampPolicy.client(
                stamp = "https://mine.example:8443",
                allowedServers = listOf("https://mine.example:8443"),
                live = live,
            ),
        )
    }

    @Test
    fun malformedStamps_neverComparable_fallBackToLive() {
        for (stamp in listOf("", "blossom.example", "https://", "not a url", "//host")) {
            assertSame(
                "stamp '$stamp' must fall back to live",
                live,
                BlossomServerStampPolicy.client(
                    stamp = stamp,
                    allowedServers = listOf("https://mine.example"),
                    live = live,
                ),
            )
        }
    }

    @Test
    fun originKey_shapes() {
        assertEquals("https://mine.example", BlossomServerStampPolicy.originKey("https://mine.example/path"))
        assertEquals("https://mine.example:8443", BlossomServerStampPolicy.originKey("https://mine.example:8443"))
        assertNull(BlossomServerStampPolicy.originKey("blossom.example"))
        assertNull(BlossomServerStampPolicy.originKey("https://"))
        assertNull(BlossomServerStampPolicy.originKey(""))
    }
}
