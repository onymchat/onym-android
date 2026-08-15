package app.onym.android.chats

import app.onym.android.transport.blossom.BlossomClient
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
    /** The user's configured Blossom endpoint URLs — the ONLY servers
     *  an attachment's `server` stamp may route a download to (see
     *  [BlossomServerStampPolicy]). Defaults to empty: stamps are
     *  ignored unless the composition root wires the configured set. */
    private val allowedStampServers: suspend () -> List<String> = { emptyList() },
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

        // Whole download under the mutex — same posture as
        // ChatVideoLoader (no inflight map, no check-and-insert races).
        return mutex.withLock {
            if (dest.exists() && dest.length() > 0) return@withLock dest
            val plaintext = try {
                // Stamped server honored ONLY within the user's own
                // configured endpoint set — see BlossomServerStampPolicy.
                val client = BlossomServerStampPolicy.client(
                    stamp = attachment.server,
                    allowedServers = allowedStampServers(),
                    live = blossomClient,
                )
                val blob = client.download(attachment.sha256)
                ChatImageCrypto.open(blob, attachment.encKey, attachment.sha256)
            } catch (_: Exception) {
                return@withLock null
            }
            // Write-temp-then-rename — see ChatVideoLoader.
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            runCatching { tmp.writeBytes(plaintext) }.getOrNull() ?: return@withLock null
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return@withLock null
            }
            dest
        }
    }

    private fun diskFile(key: String): File = File(cacheDir, "$key.m4a")
}
