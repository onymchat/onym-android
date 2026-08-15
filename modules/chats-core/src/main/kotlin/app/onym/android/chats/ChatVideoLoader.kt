package app.onym.android.chats

import app.onym.android.transport.blossom.BlossomClient
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

    /** Local decrypted MP4 file for [attachment], downloading +
     *  decrypting on first request and serving the cached file after.
     *  Returns `null` on any download / integrity / decrypt failure. */
    suspend fun file(attachment: ChatVideoAttachment): File? {
        val dest = diskFile(attachment.sha256)
        if (dest.exists() && dest.length() > 0) return dest

        // The whole download runs under the mutex, so concurrent
        // callers for the same (or any) blob serialize rather than
        // double-download — no separate inflight map, hence no
        // check-and-insert to keep suspension-safe.
        return mutex.withLock {
            // Re-check under the lock — a concurrent caller may have
            // finished the download while we waited.
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
            // Write-temp-then-rename so a crash mid-write never leaves
            // a truncated .mp4 that "exists with length > 0" and would
            // be served as-is forever after.
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            runCatching { tmp.writeBytes(plaintext) }.getOrNull() ?: return@withLock null
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return@withLock null
            }
            dest
        }
    }

    private fun diskFile(key: String): File = File(cacheDir, "$key.mp4")
}
