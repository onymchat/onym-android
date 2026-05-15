package app.onym.android.chain

import app.onym.android.support.FakeOkHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Wire-format pin for the GitHub-Releases-backed contracts manifest
 * fetcher. Reuses [FakeOkHttpClient] from PR #17's sharedTest.
 */
class ContractsManifestFetcherTest {

    @Test
    fun defaultURL_pointsAtGitHubReleasesLatestDownload() {
        // Locked against silent rename — the redirect IS the
        // deployment story; renaming this URL silently breaks
        // every install.
        assertEquals(
            "https://github.com/onymchat/onym-contracts/releases/latest/download/contracts-manifest.json",
            GitHubReleasesContractsManifestFetcher.DEFAULT_URL,
        )
    }

    @Test
    fun fetch_happyPath_returnsTypedManifestPlusRawJson() = runTest {
        val rawJson = """
            {
              "version": 1,
              "releases": [
                {
                  "release": "v0.0.2",
                  "publishedAt": "2026-05-01T15:29:00Z",
                  "contracts": [
                    { "network": "testnet", "type": "anarchy", "id": "C-A-2" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val client = FakeOkHttpClient.build { req -> FakeOkHttpClient.ok(req, rawJson) }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)

        val result = fetcher.fetch()
        assertEquals(rawJson, result.rawJson)
        assertEquals(1, result.manifest.releases.size)
        assertEquals("v0.0.2", result.manifest.releases.single().release)
    }

    @Test
    fun fetch_emptyReleasesArray_returnsEmptyManifest() = runTest {
        val client = FakeOkHttpClient.build { req ->
            FakeOkHttpClient.ok(req, """{ "version": 1, "releases": [] }""")
        }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)
        assertTrue(fetcher.fetch().manifest.releases.isEmpty())
    }

    @Test
    fun fetch_404_throwsIOException() = runTest {
        val client = FakeOkHttpClient.build { req -> FakeOkHttpClient.status(req, 404) }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)
        val thrown = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        assertTrue(thrown.message?.contains("404") == true)
    }

    @Test
    fun fetch_500_throwsIOException() = runTest {
        val client = FakeOkHttpClient.build { req -> FakeOkHttpClient.status(req, 500) }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        Unit
    }

    @Test
    fun fetch_malformedJson_throwsIOException() = runTest {
        val client = FakeOkHttpClient.build { req -> FakeOkHttpClient.ok(req, "not json {{{") }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        Unit
    }

    @Test
    fun fetch_missingReleasesField_returnsEmptyManifest() = runTest {
        // RawContractsManifest defaults `releases` to emptyList, so
        // a `{ "version": 1 }` body decodes successfully → 0
        // releases. (Distinct from the malformed-JSON case.)
        val client = FakeOkHttpClient.build { req -> FakeOkHttpClient.ok(req, """{ "version": 1 }""") }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)
        assertTrue(fetcher.fetch().manifest.releases.isEmpty())
    }

    @Test
    fun fetch_requestsTheConfiguredURL() = runTest {
        var seen: Request? = null
        val client = FakeOkHttpClient.build { req ->
            seen = req
            FakeOkHttpClient.ok(req, """{ "version": 1, "releases": [] }""")
        }
        val fetcher = GitHubReleasesContractsManifestFetcher(
            client,
            url = "https://example.com/custom/contracts-manifest.json",
        )
        fetcher.fetch()
        assertEquals("https://example.com/custom/contracts-manifest.json", seen?.url.toString())
    }
}
