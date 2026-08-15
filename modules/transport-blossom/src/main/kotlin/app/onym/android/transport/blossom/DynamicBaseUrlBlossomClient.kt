package app.onym.android.transport.blossom

import java.net.URI

/**
 * [BlossomClient] that resolves its base URL per operation instead of
 * freezing it at construction. Wraps a base-URL provider (typically
 * "the first endpoint in [BlossomServersRepository] right now") and a
 * client factory (typically an [OkHttpBlossomClient] over the shared
 * OkHttp client), so a server change in Settings — or a pick made
 * during onboarding — takes effect on the very next upload/download,
 * no relaunch needed.
 *
 * [OkHttpBlossomClient] is a thin value over a shared `OkHttpClient`
 * (connection pool + dispatcher live in the shared client), so
 * building one per operation costs nothing; the factory seam exists so
 * tests can capture the resolved URL with a fake inner client.
 *
 * Mirrors onym-ios `DynamicBaseURLBlossomClient.swift`.
 */
class DynamicBaseUrlBlossomClient(
    /** Resolves the base URL to use for the next operation. */
    private val resolveBaseUrl: suspend () -> String,
    /** Builds the underlying client for a resolved base URL. */
    private val makeClient: (String) -> BlossomClient,
) : BlossomClient {

    override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
        makeClient(resolveBaseUrl()).upload(blob, mimeType)

    override suspend fun download(sha256: String): ByteArray =
        makeClient(resolveBaseUrl()).download(sha256)

    /**
     * Pin to [serverUrl]: returns the inner client built for exactly
     * that URL, so a multi-blob send that stamped the URL into its
     * metadata uploads every blob there even if the configuration
     * changes mid-send. Falls back to `this` (live resolution) when
     * the string isn't a usable https base URL — a schemeless stamp
     * like "blossom.example" would produce requests that fail against
     * a relative URL, and anything non-https (cleartext http, odd
     * schemes) is rejected outright, matching the consent apply
     * path's https-endpoint requirement.
     */
    override fun bound(serverUrl: String): BlossomClient {
        val uri = runCatching { URI(serverUrl) }.getOrNull() ?: return this
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != "https" || host.isNullOrEmpty()) return this
        return makeClient(serverUrl)
    }
}
