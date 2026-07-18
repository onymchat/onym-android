package app.onym.android.support

import app.onym.android.chats.BlobDescriptor
import app.onym.android.chats.BlossomClient
import app.onym.android.chats.ChatImageCrypto

/**
 * In-process Blossom store for UI tests. `upload` keeps the ciphertext
 * keyed by its SHA-256 (exactly as the real server addresses blobs);
 * `download` serves it back. Lets an image send → fan-out → receive
 * round-trip run with no network. Android twin of the iOS
 * `UITestBlossomClient`.
 */
class LoopbackBlossomClient : BlossomClient {
    private val blobs = HashMap<String, ByteArray>()

    override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor {
        val sha = ChatImageCrypto.sha256Hex(blob)
        synchronized(blobs) { blobs[sha] = blob }
        return BlobDescriptor(sha256 = sha, url = "loopback://blossom/$sha", size = blob.size)
    }

    override suspend fun download(sha256: String): ByteArray =
        synchronized(blobs) { blobs[sha256] }
            ?: throw IllegalStateException("no blob $sha256")
}
