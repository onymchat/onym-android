package app.onym.android.support

import app.onym.android.discovery.DiscoveryFetching
import java.io.IOException

/**
 * Reusable test fake for [DiscoveryFetching]. URL → response table:
 *
 *  - [respond] maps a URL to exact bytes (Discovery verification is
 *    byte-exact, so fakes must be too);
 *  - [fail] maps a URL to a thrown error;
 *  - an unmapped URL throws, mimicking an unreachable host.
 *
 * Enforces the caller's size cap like the real fetcher, and tracks
 * [fetchedUrls] so tests can assert fetch order / skip behaviour
 * (e.g. refresh never touching an unpinned source).
 */
class FakeDiscoveryFetcher : DiscoveryFetching {

    private val responses = mutableMapOf<String, ByteArray>()
    private val failures = mutableMapOf<String, Throwable>()

    val fetchedUrls = mutableListOf<String>()

    fun respond(url: String, bytes: ByteArray) {
        failures.remove(url)
        responses[url] = bytes
    }

    fun fail(url: String, error: Throwable = IOException("fake failure for $url")) {
        responses.remove(url)
        failures[url] = error
    }

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
        fetchedUrls.add(url)
        failures[url]?.let { throw it }
        val bytes = responses[url]
            ?: throw IOException("FakeDiscoveryFetcher: no response mapped for $url")
        if (bytes.size > maxBytes) {
            throw IOException("$url: response exceeds $maxBytes bytes")
        }
        return bytes.copyOf()
    }
}
