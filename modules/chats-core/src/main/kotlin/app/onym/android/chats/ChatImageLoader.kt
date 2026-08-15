package app.onym.android.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Fetches + decrypts chat image blobs for rendering, with in-memory and
 * on-disk caches keyed by the blob SHA-256. Downloads the ciphertext
 * from Blossom, verifies the hash, AES-GCM-decrypts with the per-image
 * key, and caches the plaintext so re-renders (and next launch) don't
 * re-fetch. Concurrent requests for the same blob share one download.
 * Android twin of iOS `ChatImageLoader`.
 *
 * Receiving a message never touches the network — the blob is pulled
 * lazily only when a bubble renders.
 */
class ChatImageLoader(
    private val blossomClient: BlossomClient,
    private val cacheDir: File,
    /** The user's configured Blossom endpoint URLs — the ONLY servers
     *  an attachment's `server` stamp may route a download to (see
     *  [BlossomServerStampPolicy]). Defaults to empty: stamps are
     *  ignored unless the composition root wires the configured set. */
    private val allowedStampServers: suspend () -> List<String> = { emptyList() },
) {
    private val mutex = Mutex()
    private val memory = HashMap<String, Bitmap>()
    /** In-flight downloads keyed by sha, so concurrent bubble renders
     *  of the same blob share one network fetch. The check-and-insert
     *  in [load] happens in one [mutex.withLock] block with no suspend
     *  inside — a suspension between check and insert would let two
     *  callers both miss and double-download. */
    private val inflight = HashMap<String, CompletableDeferred<ByteArray?>>()

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

        // Atomic check-and-insert: exactly one caller becomes the
        // owner and performs the download; the rest await its result.
        val (deferred, isOwner) = mutex.withLock {
            inflight[key]?.let { it to false }
                ?: (CompletableDeferred<ByteArray?>().also { inflight[key] = it } to true)
        }
        val plaintext = if (isOwner) {
            val result = try {
                // Fetch from the server STAMPED into the attachment —
                // the one the sender actually uploaded to — but ONLY
                // when it is one of the user's own configured servers:
                // the stamp arrives off the wire, so it is a hint for
                // multi-server consistency among servers the USER
                // trusts, never a peer-chosen download host
                // (BlossomServerStampPolicy has the full rationale).
                // Unknown hosts and legacy null stamps use the live
                // client.
                val client = BlossomServerStampPolicy.client(
                    stamp = attachment.server,
                    allowedServers = allowedStampServers(),
                    live = blossomClient,
                )
                val blob = client.download(attachment.sha256)
                ChatImageCrypto.open(blob, attachment.encKey, attachment.sha256)
            } catch (_: Exception) {
                null
            }
            deferred.complete(result)
            mutex.withLock { inflight.remove(key) }
            result
        } else {
            deferred.await()
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size) ?: return null
        writeAtomic(file, plaintext)
        mutex.withLock { memory[key] = bitmap }
        return bitmap
    }

    /** Sender-side warm cache: prime the decrypted image so the sender
     *  renders instantly without re-downloading. */
    suspend fun prime(sha256: String, plaintext: ByteArray) {
        writeAtomic(diskFile(sha256), plaintext)
        BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size)?.let { bmp ->
            mutex.withLock { memory[sha256] = bmp }
        }
    }

    private fun diskFile(key: String): File = File(cacheDir, "$key.img")

    /** Write-temp-then-rename so a crash mid-write never leaves a
     *  truncated cache file — a half-written blob would hash to the
     *  wrong digest yet still partially decode on the next launch. */
    private fun writeAtomic(dest: File, data: ByteArray) {
        runCatching {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
            }
        }
    }
}
