package app.onym.android.transport.blossom

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [DynamicBaseUrlBlossomClient] — per-operation base-URL resolution +
 * the `bound(serverUrl)` pinning contract. Mirrors onym-ios
 * `DynamicBaseURLBlossomClientTests.swift`.
 */
class DynamicBaseUrlBlossomClientTest {

    /** Inner fake that records which base URL it was built for. */
    private class RecordingClient(val baseUrl: String, val log: MutableList<String>) : BlossomClient {
        override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor {
            log.add("upload@$baseUrl")
            return BlobDescriptor(sha256 = "00", url = "$baseUrl/00", size = blob.size)
        }

        override suspend fun download(sha256: String): ByteArray {
            log.add("download@$baseUrl")
            return ByteArray(0)
        }
    }

    private fun makeClient(
        urls: ArrayDeque<String>,
        log: MutableList<String> = mutableListOf(),
    ): Pair<DynamicBaseUrlBlossomClient, MutableList<String>> {
        val client = DynamicBaseUrlBlossomClient(
            resolveBaseUrl = { urls.first().also { if (urls.size > 1) urls.removeFirst() } },
            makeClient = { url -> RecordingClient(url, log) },
        )
        return client to log
    }

    @Test
    fun operations_resolveBaseUrlPerCall_notAtConstruction() = runTest {
        val (client, log) = makeClient(
            ArrayDeque(listOf("https://first.example", "https://second.example")),
        )

        client.upload(ByteArray(4), "image/jpeg")
        client.download("aa")

        // First op sees the first URL; the configuration "changed" in
        // between and the second op sees the new one — no relaunch.
        assertEquals(listOf("upload@https://first.example", "download@https://second.example"), log)
    }

    @Test
    fun bound_pinsEveryOperationToTheGivenHttpsUrl() = runTest {
        val (client, log) = makeClient(
            ArrayDeque(listOf("https://live.example")),
        )

        val bound = client.bound("https://pinned.example")
        bound.upload(ByteArray(4), "image/jpeg")
        bound.download("aa")

        assertEquals(listOf("upload@https://pinned.example", "download@https://pinned.example"), log)
    }

    @Test
    fun bound_rejectsHttp_fallsBackToLiveResolution() = runTest {
        val (client, _) = makeClient(ArrayDeque(listOf("https://live.example")))
        assertSame(client, client.bound("http://cleartext.example"))
    }

    @Test
    fun bound_rejectsSchemelessAndMalformed_fallsBackToLiveResolution() {
        val (client, _) = makeClient(ArrayDeque(listOf("https://live.example")))
        assertSame(client, client.bound("blossom.example"))
        assertSame(client, client.bound(""))
        assertSame(client, client.bound("https://"))
        assertSame(client, client.bound("not a url"))
    }

    @Test
    fun fixedBaseUrlClients_boundIsIdentity() {
        // The interface default: clients with a fixed base URL return
        // themselves — the UI-test in-memory client relies on this.
        val log = mutableListOf<String>()
        val fixed = RecordingClient("https://fixed.example", log)
        assertSame(fixed, fixed.bound("https://other.example"))
    }
}
