package app.onym.android.chain

import app.onym.android.foundation.TrustedAssetVerifier
import app.onym.android.support.FakeOkHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

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

    // ---- H-3: detached-signature verification --------------------

    private val manifestJson = """{ "version": 1, "releases": [] }"""

    @Test
    fun fetch_softMode_unsignedAsset_stillReturnsDecodedManifest() = runTest {
        // No `.sig` asset published (404) + default (soft-mode) verifier:
        // the fetch must still decode the manifest so today's unsigned
        // releases keep working.
        val client = FakeOkHttpClient.build { req ->
            if (req.url.toString().endsWith(".sig")) FakeOkHttpClient.status(req, 404)
            else FakeOkHttpClient.ok(req, manifestJson)
        }
        val fetcher = GitHubReleasesContractsManifestFetcher(client)
        assertTrue(fetcher.fetch().manifest.releases.isEmpty())
    }

    @Test
    fun fetch_validSignature_verifiesAndReturnsManifest() = runTest {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey().encoded
        val sig = Ed25519Signer().apply {
            init(true, priv)
            val body = manifestJson.toByteArray(Charsets.UTF_8)
            update(body, 0, body.size)
        }.generateSignature()

        val client = FakeOkHttpClient.build { req ->
            if (req.url.toString().endsWith(".sig")) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sig.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            } else {
                FakeOkHttpClient.ok(req, manifestJson)
            }
        }
        val fetcher = GitHubReleasesContractsManifestFetcher(
            client,
            verifier = TrustedAssetVerifier(publicKey = pub, enforce = true),
        )
        // Enforce mode + valid signature: must not throw, returns manifest.
        assertTrue(fetcher.fetch().manifest.releases.isEmpty())
    }

    @Test
    fun fetch_enforceMode_missingSignature_throws() = runTest {
        val client = FakeOkHttpClient.build { req ->
            if (req.url.toString().endsWith(".sig")) FakeOkHttpClient.status(req, 404)
            else FakeOkHttpClient.ok(req, manifestJson)
        }
        val fetcher = GitHubReleasesContractsManifestFetcher(
            client,
            verifier = TrustedAssetVerifier(
                publicKey = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded,
                enforce = true,
            ),
        )
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { fetcher.fetch() }
        }
        Unit
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
