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
 * rules: HTTPS-only requests, redirects handled MANUALLY — at most 3
 * per fetch, HTTPS-to-HTTPS only, refusing IP-literal targets —
 * a 60-second per-request timeout, and a streamed size cap so an
 * oversize response is abandoned at the bound instead of buffered
 * whole.
 */
class OkHttpDiscoveryFetcher(
    httpClient: OkHttpClient,
) : DiscoveryFetching {

    private val client: OkHttpClient = httpClient.newBuilder()
        // Redirects are validated per hop below — OkHttp's built-in
        // following (up to 20 hops, cross-protocol) does not meet §7.
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(java.time.Duration.ofSeconds(60))
        .connectTimeout(java.time.Duration.ofSeconds(60))
        .readTimeout(java.time.Duration.ofSeconds(60))
        .build()

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            var currentUrl = url.toHttpUrl()
            var redirects = 0
            var result: ByteArray? = null
            while (result == null) {
                if (!currentUrl.isHttps) throw IOException("$currentUrl: https only")
                val request = Request.Builder().url(currentUrl).build()
                val bytes = client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        // §7: ≤ 3 redirects per fetch, HTTPS-to-HTTPS
                        // only, never toward an IP literal.
                        redirects += 1
                        if (redirects > MAX_REDIRECTS) {
                            throw IOException("$url: more than $MAX_REDIRECTS redirects")
                        }
                        val location = response.header("Location")
                            ?: throw IOException("$url: redirect without Location")
                        val target = currentUrl.resolve(location)
                            ?: throw IOException("$url: unresolvable redirect target")
                        if (!target.isHttps) {
                            throw IOException("$url: redirect to non-HTTPS target refused")
                        }
                        if (isIpLiteral(target.host)) {
                            throw IOException("$url: redirect to IP-literal target refused")
                        }
                        currentUrl = target
                        return@use null
                    }
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
                    body.source().use { source ->
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
                }
                result = bytes
            }
            result
        }

    private companion object {
        const val MAX_REDIRECTS = 3

        /** Dotted-decimal, bracketless-IPv6, integer-, or hex-form IP
         *  literal hosts — §7 refuses redirects toward all of them. */
        fun isIpLiteral(host: String): Boolean {
            if (':' in host) return true // IPv6 (OkHttp strips brackets)
            val labels = host.split('.')
            if (labels.all { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }) return true
            val last = labels.last().lowercase()
            return last.startsWith("0x")
        }
    }
}
