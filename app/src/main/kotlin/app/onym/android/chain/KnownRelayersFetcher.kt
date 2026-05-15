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
 * Network seam for "fetch the latest list of relayers". Production
 * impl ([GitHubReleasesKnownRelayersFetcher]) talks to GitHub's
 * `releases/latest/download/<asset>` redirect — public, no auth,
 * no API rate limit. Tests substitute a fake.
 *
 * Mirrors `KnownRelayersFetcher` from onym-ios PR #18.
 */
interface KnownRelayersFetcher {
    /**
     * Fetch the current published list. Throws on any network /
     * parse failure — callers (`RelayerRepository.start`) are
     * responsible for keeping the cached list intact on error.
     */
    suspend fun fetch(): List<RelayerEndpoint>
}

/**
 * Pulls `relayers.json` from the latest GitHub Release of
 * `onymchat/onym-relayer`. Default URL is the
 * `releases/latest/download/<asset>` redirect — server-side that
 * resolves to the asset attached to whichever release is currently
 * tagged "latest", so publishing a new release with the same asset
 * filename is the entire deployment story (no app update).
 *
 * @param httpClient OkHttp client, injected for tests. Production
 *        passes a default-configured client; tests pass one built
 *        with `support/FakeOkHttpClient.kt`.
 * @param url Override only for tests; the production default is
 *        load-bearing — see [DEFAULT_URL].
 */
class GitHubReleasesKnownRelayersFetcher(
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
) : KnownRelayersFetcher {

    override suspend fun fetch(): List<RelayerEndpoint> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            // Typed errors so the repository can map each kind to a
            // distinct localised user-facing message
            // ([RelayersFetchError]). Network-layer failures stay as
            // raw [IOException]; only HTTP-status + JSON-decode get
            // wrapped here.
            if (!response.isSuccessful) {
                throw RelayersFetchError.BadStatus(response.code)
            }
            val body = response.body?.string() ?: throw IOException("empty response body")
            try {
                jsonFormat.decodeFromString(KnownRelayersDocument.serializer(), body).relayers
            } catch (e: SerializationException) {
                throw RelayersFetchError.MalformedDocument(e)
            }
        }
    }

    companion object {
        /**
         * Pinned by [KnownRelayersFetcherTest.defaultURL_pointsAtGitHubReleasesLatestDownload].
         * Renaming this string silently breaks every install — the
         * server-side redirect is the entire deployment hot-path.
         */
        const val DEFAULT_URL = "https://github.com/onymchat/onym-relayer/releases/latest/download/relayers.json"

        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
