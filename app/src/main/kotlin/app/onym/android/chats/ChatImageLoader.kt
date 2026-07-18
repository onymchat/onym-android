package app.onym.android.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Fetches + decrypts chat image blobs for rendering, with in-memory and
 * on-disk caches keyed by the blob SHA-256. Downloads the ciphertext
 * from Blossom, verifies the hash, AES-GCM-decrypts with the per-image
 * key, and caches the plaintext so re-renders (and next launch) don't
 * re-fetch. Android twin of iOS `ChatImageLoader`.
 *
 * Receiving a message never touches the network — the blob is pulled
 * lazily only when a bubble renders.
 */
class ChatImageLoader(
    private val blossomClient: BlossomClient,
    private val cacheDir: File,
) {
    private val mutex = Mutex()
    private val memory = HashMap<String, Bitmap>()

    init {
        runCatching { cacheDir.mkdirs() }
    }

    /** Decrypted bitmap for [attachment], or `null` on any download /
     *  integrity / decrypt failure. */
    suspend fun load(attachment: ChatImageAttachment): Bitmap? {
        val key = attachment.sha256
        mutex.withLock { memory[key] }?.let { return it }

        val file = diskFile(key)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { bmp ->
                mutex.withLock { memory[key] = bmp }
                return bmp
            }
        }

        val plaintext = try {
            val blob = blossomClient.download(attachment.sha256)
            ChatImageCrypto.open(blob, attachment.encKey, attachment.sha256)
        } catch (_: Exception) {
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size) ?: return null
        runCatching { file.writeBytes(plaintext) }
        mutex.withLock { memory[key] = bitmap }
        return bitmap
    }

    /** Sender-side warm cache: prime the decrypted image so the sender
     *  renders instantly without re-downloading. */
    suspend fun prime(sha256: String, plaintext: ByteArray) {
        runCatching { diskFile(sha256).writeBytes(plaintext) }
        BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size)?.let { bmp ->
            mutex.withLock { memory[sha256] = bmp }
        }
    }

    private fun diskFile(key: String): File = File(cacheDir, "$key.img")
}
