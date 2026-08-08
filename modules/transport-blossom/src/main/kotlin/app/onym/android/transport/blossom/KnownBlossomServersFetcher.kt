package app.onym.android.transport.blossom

import app.onym.android.foundation.TrustedAssetVerifier
import app.onym.android.foundation.fetchDetachedSignature
import app.onym.android.foundation.signatureUrlFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Network seam for "fetch the Onym-published default Blossom-server
 * list". Production impl talks to GitHub's
 * `releases/latest/download/<asset>` redirect. Tests substitute a fake.
 *
 * Mirrors [app.onym.android.transport.nostr.KnownNostrRelaysFetcher].
 */
interface KnownBlossomServersFetcher {
    /** Fetch the current published list. Throws on any network / parse
     *  failure — callers keep the on-device list intact on error. */
    suspend fun fetch(): List<BlossomServerEndpoint>
}

/** Wire wrapper for `blossom-servers.json`: `{ "version": 1, "servers": [...] }`. */
@Serializable
internal data class KnownBlossomServersDocument(
    val version: Int,
    val servers: List<BlossomServerEndpoint>,
)

/** Pulls `blossom-servers.json` from the latest GitHub Release. */
class GitHubReleasesKnownBlossomServersFetcher(
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
    private val verifier: TrustedAssetVerifier = TrustedAssetVerifier(),
) : KnownBlossomServersFetcher {

    override suspend fun fetch(): List<BlossomServerEndpoint> = withContext(Dispatchers.IO) {
        // H-3: verify raw asset bytes against the offline-signing key
        // before trusting the Blossom server list (soft mode by default).
        val signature = httpClient.fetchDetachedSignature(signatureUrlFor(url))
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("bad status ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("empty response body")
            verifier.gate("blossom-servers.json", bytes, signature)
            val body = String(bytes, Charsets.UTF_8)
            jsonFormat.decodeFromString(KnownBlossomServersDocument.serializer(), body).servers
        }
    }

    companion object {
        internal const val DEFAULT_URL =
            "https://github.com/onymchat/onym-relayer/releases/latest/download/blossom-servers.json"
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
