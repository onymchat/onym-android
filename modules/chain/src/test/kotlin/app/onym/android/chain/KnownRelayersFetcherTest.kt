package app.onym.android.chain

import app.onym.android.support.FakeOkHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format pin for the GitHub-Releases-backed fetcher. Uses a
 * hand-rolled OkHttp [okhttp3.Interceptor] (via [FakeOkHttpClient])
 * to return canned responses per request — no `MockWebServer`.
 *
 * Mirrors `KnownRelayersFetcherTests` from onym-ios PR #18.
 */
class KnownRelayersFetcherTest {

    @Test
    fun defaultURL_pointsAtGitHubReleasesLatestDownload() {
        // Locked against silent rename — server-side this redirect
        // is the entire deployment story; if the path here drifts,
        // every install loses the ability to discover relayers.
        assertEquals(
            "https://github.com/onymchat/onym-relayer/releases/latest/download/relayers.json",
            GitHubReleasesKnownRelayersFetcher.DEFAULT_URL,
        )
    }

    @Test
    fun fetch_happyPath_parsesTwoRelayerDocument() = runTest {
        // PR #23 wire shape: `networks` is a plural list. The
        // [RelayerEndpointSerializer] also accepts the legacy singular
        // `network` field — pinned separately in
        // `RelayerEndpointSchemaTest`.
        val json = """
            {
              "version": 1,
              "relayers": [
                { "name": "Onym Official Testnet", "url": "https://relayer-testnet.onym.chat", "networks": ["testnet"] },
                { "name": "Onym Official Mainnet", "url": "https://relayer.onym.chat",         "networks": ["public"]  }
              ]
            }
        """.trimIndent()
        val client = FakeOkHttpClient.build { req -> FakeOkHttpClient.ok(req, json) }
        val fetcher = GitHubReleasesKnownRelayersFetcher(client)

        val list = fetcher.fetch()
        assertEquals(2, list.size)
        assertEquals(RelayerEndpoint("Onym Official Testnet", "https://relayer-testnet.onym.chat", listOf("testnet")), list[0])
        assertEquals(RelayerEndpoint("Onym Official Mainnet", "https://relayer.onym.chat", listOf("public")), list[1])
    }

    @Test
    fun fetch_emptyRelayersArray_returnsEmptyList() = runTest {
        val client = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.ok(req, """{ "version": 1, "relayers": [] }""")
        }
        val fetcher = GitHubReleasesKnownRelayersFetcher(client)

        assertTrue(fetcher.fetch().isEmpty())
    }

    @Test
    fun fetch_404_throwsBadStatus() = runTest {
        // PR #23 raises typed errors (`RelayersFetchError.BadStatus`)
        // so the repository can map each kind to its own localised
        // user-facing message.
        val client = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.status(req, 404, "Not Found")
        }
        val fetcher = GitHubReleasesKnownRelayersFetcher(client)

        val thrown = assertThrows(RelayersFetchError.BadStatus::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        assertEquals(404, thrown.code)
    }

    @Test
    fun fetch_500_throwsBadStatus() = runTest {
        val client = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.status(req, 500, "Internal Server Error")
        }
        val fetcher = GitHubReleasesKnownRelayersFetcher(client)

        val thrown = assertThrows(RelayersFetchError.BadStatus::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        assertEquals(500, thrown.code)
    }

    @Test
    fun fetch_malformedJson_throwsMalformedDocument() = runTest {
        val client = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.ok(req, "not really json {{{")
        }
        val fetcher = GitHubReleasesKnownRelayersFetcher(client)

        assertThrows(RelayersFetchError.MalformedDocument::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        Unit
    }

    @Test
    fun fetch_missingRelayersField_throwsMalformedDocument() = runTest {
        val client = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.ok(req, """{ "version": 1 }""")
        }
        val fetcher = GitHubReleasesKnownRelayersFetcher(client)

        assertThrows(RelayersFetchError.MalformedDocument::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        Unit
    }

    @Test
    fun fetch_requestsTheConfiguredURL() = runTest {
        var seen: Request? = null
        val client = FakeOkHttpClient.build { req ->
            seen = req
            FakeOkHttpClient.ok(req, """{ "version": 1, "relayers": [] }""")
        }
        val fetcher = GitHubReleasesKnownRelayersFetcher(
            client,
            url = "https://example.com/custom/relayers.json",
        )

        fetcher.fetch()
        assertEquals("https://example.com/custom/relayers.json", seen?.url.toString())
    }
}
