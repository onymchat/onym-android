package app.onym.android.chats

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Fetches + decrypts chat voice blobs for playback, caching the decrypted
 * `.m4a` on disk keyed by the blob SHA-256. Downloads the ciphertext from
 * Blossom, verifies the hash, AES-GCM-decrypts with the per-clip key, and
 * writes a plaintext `.m4a` a `MediaPlayer` plays from. Android twin of iOS
 * `ChatVoiceLoader`.
 *
 * The audio blob is only pulled when the user taps play — the bubble's
 * waveform + duration render from the descriptor alone, so nothing
 * downloads on receipt. Sibling to [ChatVideoLoader].
 */
class ChatVoiceLoader(
    private val blossomClient: BlossomClient,
    private val cacheDir: File,
) {
    private val mutex = Mutex()

    init {
        runCatching { cacheDir.mkdirs() }
    }

    /** Local decrypted `.m4a` file for [attachment], downloading +
     *  decrypting on first request and serving the cached file after.
     *  Returns `null` on any download / integrity / decrypt failure. */
    suspend fun file(attachment: ChatVoiceAttachment): File? {
        val dest = diskFile(attachment.sha256)
        if (dest.exists() && dest.length() > 0) return dest

        return mutex.withLock {
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

    private fun diskFile(key: String): File = File(cacheDir, "$key.m4a")
}
