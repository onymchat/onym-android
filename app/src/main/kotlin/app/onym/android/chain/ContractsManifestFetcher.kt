package app.onym.android.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Network seam for "fetch the current contracts manifest". Same
 * shape as [app.onym.android.chain.KnownRelayersFetcher] from
 * PR #17 — production impl talks to GitHub's
 * `releases/latest/download/<asset>` redirect (public, no auth,
 * no API rate limit), tests substitute a fake.
 */
interface ContractsManifestFetcher {
    /**
     * Returns the typed manifest plus the raw JSON body. Callers
     * (i.e. [ContractsRepository]) cache the raw JSON to disk so
     * subsequent decodes don't have to re-encode the typed value.
     */
    suspend fun fetch(): FetchResult

    /** Typed manifest + raw JSON pair. */
    data class FetchResult(val manifest: ContractsManifest, val rawJson: String)
}

/**
 * Pulls `contracts-manifest.json` from the latest GitHub Release
 * of `onymchat/onym-contracts`. Default URL is the
 * `releases/latest/download/<asset>` redirect — server-side that
 * resolves to whatever release is currently tagged "latest", so
 * publishing a new release with the same asset filename + the CI
 * step that re-attaches the regenerated manifest is the entire
 * deployment story (no app update).
 *
 * @param httpClient OkHttp client, injected for tests.
 * @param url Override only for tests; the production default is
 *        load-bearing — see [DEFAULT_URL].
 */
class GitHubReleasesContractsManifestFetcher(
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
) : ContractsManifestFetcher {

    override suspend fun fetch(): ContractsManifestFetcher.FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET $url returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("empty response body")
            val raw = try {
                jsonFormat.decodeFromString(RawContractsManifest.serializer(), body)
            } catch (e: SerializationException) {
                throw IOException("invalid contracts-manifest.json: ${e.message}", e)
            }
            ContractsManifestFetcher.FetchResult(
                manifest = ContractsManifest.fromRaw(raw),
                rawJson = body,
            )
        }
    }

    companion object {
        /** Pinned by `ContractsManifestFetcherTest.defaultURL_…` —
         *  renaming silently breaks every install. */
        const val DEFAULT_URL = "https://github.com/onymchat/onym-contracts/releases/latest/download/contracts-manifest.json"

        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
