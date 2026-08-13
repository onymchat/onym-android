package app.onym.android.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Network seam for "fetch bytes from a Discovery URL, bounded". Same
 * shape as :chain's `KnownRelayersFetcher` — production impl is
 * OkHttp, tests substitute `FakeDiscoveryFetcher` from this module's
 * testFixtures.
 *
 * Returns *exact* bytes: digests and signatures are computed over the
 * published bytes, so no layer between the socket and the verifier
 * may re-serialize.
 */
interface DiscoveryFetching {
    /**
     * Fetch [url] and return the exact response bytes.
     *
     * @param maxBytes hard cap from the §7 bounds table; a longer
     *        response throws [IOException] without buffering the rest.
     */
    suspend fun fetch(url: String, maxBytes: Int): ByteArray
}

/**
 * OkHttp-backed [DiscoveryFetching] with the profile's §7 transport
 * rules: HTTPS-only requests, HTTPS-to-HTTPS redirects only
 * (`followSslRedirects = false` disables protocol-switching
 * redirects), and a streamed size cap so an oversize response is
 * abandoned at the bound instead of buffered whole.
 */
class OkHttpDiscoveryFetcher(
    httpClient: OkHttpClient,
) : DiscoveryFetching {

    private val client: OkHttpClient = httpClient.newBuilder()
        .followSslRedirects(false)
        .build()

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val httpUrl = url.toHttpUrl()
            if (!httpUrl.isHttps) throw IOException("$url: https only")
            val request = Request.Builder().url(httpUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GET $url returned HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("empty response body")
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) {
                    throw IOException("$url: response exceeds $maxBytes bytes")
                }
                // Stream at most maxBytes + 1: one extra byte proves an
                // unsized (chunked) response is over the cap.
                val bytes = body.source().use { source ->
                    val buffer = okio.Buffer()
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer, (maxBytes + 1L) - total)
                        if (read == -1L) break
                        total += read
                        if (total > maxBytes) {
                            throw IOException("$url: response exceeds $maxBytes bytes")
                        }
                    }
                    buffer.readByteArray()
                }
                bytes
            }
        }
}
