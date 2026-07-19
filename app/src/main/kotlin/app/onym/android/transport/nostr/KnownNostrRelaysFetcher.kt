package app.onym.android.transport.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Network seam for "fetch the Onym-published default Nostr-relay list".
 * Production impl talks to GitHub's `releases/latest/download/<asset>`
 * redirect — public, no auth, no API rate limit. Tests substitute a fake.
 *
 * Mirrors [app.onym.android.chain.KnownRelayersFetcher].
 */
interface KnownNostrRelaysFetcher {
    /** Fetch the current published list. Throws on any network / parse
     *  failure — callers keep the on-device list (seed or last good
     *  fetch) intact on error. */
    suspend fun fetch(): List<NostrRelayEndpoint>
}

/** Wire wrapper for `nostr-relays.json`: `{ "version": 1, "relays": [...] }`. */
@Serializable
data class KnownNostrRelaysDocument(
    val version: Int,
    val relays: List<NostrRelayEndpoint>,
)

/** Pulls `nostr-relays.json` from the latest GitHub Release. */
class GitHubReleasesKnownNostrRelaysFetcher(
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
) : KnownNostrRelaysFetcher {

    override suspend fun fetch(): List<NostrRelayEndpoint> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("bad status ${response.code}")
            val body = response.body?.string() ?: throw IOException("empty response body")
            jsonFormat.decodeFromString(KnownNostrRelaysDocument.serializer(), body).relays
        }
    }

    companion object {
        const val DEFAULT_URL =
            "https://github.com/onymchat/onym-relayer/releases/latest/download/nostr-relays.json"
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
