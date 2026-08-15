package app.onym.android.chats

import app.onym.android.transport.blossom.BlobDescriptor
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/**
 * [ChatImageLoader.fetchPlaintext] — the shared-download dedup and its
 * cancellation behavior. Exercised through the plaintext seam so the
 * JVM tests never touch the Android Bitmap framework.
 *
 * The review-critical properties:
 *  - a waiter's cancellation must never null-poison the shared
 *    deferred for other, still-visible waiters (the download runs on
 *    the loader's own scope, and CancellationException is rethrown,
 *    never swallowed into a null completion);
 *  - a failed (null) download is evicted from the inflight map, so
 *    the next request retries instead of resolving null forever.
 */
class ChatImageLoaderInflightTest {

    private val plaintext = ByteArray(96) { it.toByte() }
    private val sealed = ChatImageCrypto.seal(plaintext)

    private fun attachment(server: String? = null) = ChatImageAttachment(
        sha256 = sealed.sha256Hex,
        mimeType = "image/jpeg",
        byteSize = sealed.blob.size,
        width = 4,
        height = 4,
        encKey = sealed.key,
        blurhash = "LEHV6nWB2yk8",
        server = server,
    )

    /** Client whose downloads park on [gate] until released. */
    private class GatedClient(
        private val blob: ByteArray,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : BlossomClient {
        var downloads = 0

        override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
            throw IllegalStateException("not used")

        override suspend fun download(sha256: String): ByteArray {
            downloads++
            gate.await()
            return blob
        }
    }

    private fun tempDir() = Files.createTempDirectory("image-loader-inflight").toFile()

    @Test
    fun cancellingOneWaiter_midDownload_doesNotPoisonTheOther() = runTest {
        val client = GatedClient(sealed.blob)
        val loader = ChatImageLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            downloadScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        // Two bubbles for the same sha: the first (owner-requester)
        // scrolls out of composition and is cancelled mid-download.
        val first = launch { loader.fetchPlaintext(attachment()) }
        val second = async { loader.fetchPlaintext(attachment()) }
        runCurrent()
        assertEquals(1, client.downloads)

        first.cancel()
        runCurrent()
        client.gate.complete(Unit)

        // The still-visible waiter gets the bytes — the download ran on
        // the loader's scope, so the requester's cancellation neither
        // cancelled it nor completed the shared deferred with null.
        assertArrayEquals(plaintext, second.await())
        assertEquals(1, client.downloads)
    }

    @Test
    fun concurrentRequests_shareOneDownload() = runTest {
        val client = GatedClient(sealed.blob)
        val loader = ChatImageLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            downloadScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        val a = async { loader.fetchPlaintext(attachment()) }
        val b = async { loader.fetchPlaintext(attachment()) }
        runCurrent()
        client.gate.complete(Unit)

        assertArrayEquals(plaintext, a.await())
        assertArrayEquals(plaintext, b.await())
        assertEquals(1, client.downloads)
    }

    @Test
    fun failedDownload_isEvicted_nextRequestRetries() = runTest {
        var fail = true
        val client = object : BlossomClient {
            var downloads = 0
            override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
                throw IllegalStateException("not used")
            override suspend fun download(sha256: String): ByteArray {
                downloads++
                if (fail) throw IOException("network down")
                return sealed.blob
            }
        }
        val loader = ChatImageLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            downloadScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        // Genuine failure resolves null once…
        assertNull(loader.fetchPlaintext(attachment()))

        // …but the entry is gone, so the next request retries fresh
        // instead of resolving null for the rest of the process.
        fail = false
        assertArrayEquals(plaintext, loader.fetchPlaintext(attachment()))
        assertEquals(2, client.downloads)
    }

    @Test
    fun cancelledWaiter_thenNewRequest_stillCompletesFromTheSharedDownload() = runTest {
        // Contended-cancel shape: the only waiter cancels mid-download.
        // The download itself keeps running on the loader scope; its
        // entry is evicted in the same non-cancellable path that
        // completes it, so a follow-up request either joins it or
        // starts fresh — never observes a leaked completed-null entry.
        val client = GatedClient(sealed.blob)
        val loader = ChatImageLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            downloadScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        val only = launch { loader.fetchPlaintext(attachment()) }
        runCurrent()
        only.cancel()
        runCurrent()
        client.gate.complete(Unit)
        runCurrent()

        assertArrayEquals(plaintext, loader.fetchPlaintext(attachment()))
    }
}
