package app.onym.android.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    /** Scope the shared downloads run in. Deliberately independent of
     *  any requesting composition: `load` is called from
     *  `LaunchedEffect`s, and a bubble scrolled out of composition
     *  cancels its call — that must never cancel (or null-poison) the
     *  download other, still-visible bubbles are awaiting. */
    private val downloadScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private val memory = HashMap<String, Bitmap>()
    /** In-flight downloads keyed by sha, so concurrent bubble renders
     *  of the same blob share one network fetch. The check-and-insert
     *  in [fetchPlaintext] happens in one [mutex.withLock] block with
     *  no suspend inside — a suspension between check and insert would
     *  let two callers both miss and double-download. */
    private val inflight = HashMap<String, Deferred<ByteArray?>>()

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

        val plaintext = fetchPlaintext(attachment) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size) ?: return null
        writeAtomic(file, plaintext)
        mutex.withLock { memory[key] = bitmap }
        return bitmap
    }

    /**
     * Download + integrity-check + decrypt [attachment]'s blob, sharing
     * one download among concurrent callers. Returns `null` only on a
     * genuine download / integrity / decrypt failure — a caller's own
     * cancellation propagates as [CancellationException] and never
     * completes the shared deferred, because the download runs on
     * [downloadScope], not in the caller's job. A failed (null) entry
     * is removed from [inflight] before its waiters observe it, so the
     * next request for the same sha retries fresh.
     *
     * Public for two callers beyond [load]: JVM unit tests (dedup +
     * stamp-policy without the Android [BitmapFactory] dependency),
     * and the report flow — a disclosed photo must be the EXACT
     * plaintext bytes the recipient holds, never a re-encoded
     * `Bitmap`, because the sender's media commitment hashes these
     * bytes and anything re-encoded would fail verification.
     */
    suspend fun fetchPlaintext(attachment: ChatImageAttachment): ByteArray? {
        val key = attachment.sha256
        // Atomic check-and-insert: the first caller installs a download
        // running on the loader's own scope; the rest await it.
        val deferred = mutex.withLock {
            inflight[key] ?: downloadScope.async {
                try {
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
                } catch (e: CancellationException) {
                    // Never swallow cancellation into a null completion —
                    // a cancelled deferred rethrows at every waiter's
                    // `await` instead of poisoning them all with null.
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    // Evict in the SAME (non-cancellable) path that
                    // completes the deferred — an eviction parked behind
                    // a cancellable lock suspension could throw after
                    // completion and leak a permanently-null entry.
                    withContext(NonCancellable) {
                        mutex.withLock { inflight.remove(key) }
                    }
                }
            }.also { inflight[key] = it }
        }
        return deferred.await()
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
     *  wrong digest yet still partially decode on the next launch. The
     *  temp name is unique per write: `load` waiters and [prime] can
     *  write the same sha concurrently, and a shared temp path would
     *  let one caller's rename land while another is still streaming
     *  into it — re-opening the destination-inode truncation window
     *  this helper exists to close. */
    private fun writeAtomic(dest: File, data: ByteArray) {
        runCatching {
            val tmp = File(dest.parentFile, dest.name + ".tmp-" + UUID.randomUUID())
            tmp.writeBytes(data)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
            }
        }
    }
}
