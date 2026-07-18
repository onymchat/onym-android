package app.onym.android.chats

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Fetches + decrypts chat video blobs for playback, caching the
 * decrypted MP4 on disk keyed by the blob SHA-256. Downloads the
 * ciphertext from Blossom, verifies the hash, AES-GCM-decrypts with the
 * per-video key, and writes a plaintext `.mp4` the player streams from.
 * Android twin of iOS `ChatVideoLoader`.
 *
 * The video blob is only ever pulled when the user taps play — the
 * poster (an image attachment) renders from [ChatImageLoader] without
 * touching the (large) video. Sibling to [ChatImageLoader], but it
 * returns a file rather than a decoded bitmap so ExoPlayer can play it.
 */
class ChatVideoLoader(
    private val blossomClient: BlossomClient,
    private val cacheDir: File,
) {
    private val mutex = Mutex()

    init {
        runCatching { cacheDir.mkdirs() }
    }

    /** Local decrypted MP4 file for [attachment], downloading +
     *  decrypting on first request and serving the cached file after.
     *  Returns `null` on any download / integrity / decrypt failure. */
    suspend fun file(attachment: ChatVideoAttachment): File? {
        val dest = diskFile(attachment.sha256)
        if (dest.exists() && dest.length() > 0) return dest

        return mutex.withLock {
            // Re-check under the lock — a concurrent caller may have
            // finished the download while we waited.
            if (dest.exists() && dest.length() > 0) return@withLock dest
            val plaintext = try {
                val blob = blossomClient.download(attachment.sha256)
                ChatImageCrypto.open(blob, attachment.encKey, attachment.sha256)
            } catch (_: Exception) {
                return@withLock null
            }
            runCatching { dest.writeBytes(plaintext) }.getOrNull() ?: return@withLock null
            dest
        }
    }

    private fun diskFile(key: String): File = File(cacheDir, "$key.mp4")
}
