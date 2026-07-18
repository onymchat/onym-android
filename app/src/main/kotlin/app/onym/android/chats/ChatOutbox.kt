package app.onym.android.chats

import java.io.File

/**
 * On-disk store of the **encrypted** attachment blobs for outgoing media
 * that hasn't confirmed sent yet, keyed by the blob SHA-256. Android twin
 * of iOS `ChatOutbox`.
 *
 * Sending inserts the optimistic bubble before the upload, so a failed
 * upload/fan-out leaves a `FAILED` message the user can resend. Resend
 * must re-upload the exact same ciphertext (Blossom addresses blobs by
 * SHA-256, and the attachment descriptor already committed to that hash),
 * so we can't re-seal — a fresh nonce would change the bytes. Persisting
 * the sealed blob here lets resend re-upload the identical ciphertext and
 * survives an app restart. Entries are evicted on confirmed send/delete.
 */
class ChatOutbox(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    /** Persist the sealed ciphertext [blob] under its [sha]. */
    fun store(sha: String, blob: ByteArray) {
        runCatching { file(sha).writeBytes(blob) }
    }

    /** The stored ciphertext for [sha], or `null` if evicted / absent. */
    fun load(sha: String): ByteArray? =
        runCatching { file(sha).takeIf { it.exists() }?.readBytes() }.getOrNull()

    /** Drop the stored blob for [sha] (on confirmed send or delete). */
    fun remove(sha: String) {
        runCatching { file(sha).delete() }
    }

    private fun file(sha: String): File = File(dir, "$sha.blob")
}
