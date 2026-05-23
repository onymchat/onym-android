package app.onym.android.group

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Image pipeline for group avatars. Produces a small, square JPEG that
 * fits inside a sealed NOSTR envelope: **square, centre-cropped, 256×256
 * px, JPEG, encoded under a 16 KB raw budget** (base64 on the wire is
 * ~22 KB). The budget keeps sealed envelopes within relay event-size
 * limits — deliberately undersized.
 *
 * The raw JPEG bytes are what gets stored on [ChatGroup.avatar] and sent
 * on the wire (base64) in [GroupInvitationPayload.avatar] /
 * [GroupAvatarPayload.avatar]. Cross-platform: iOS produces the same
 * shape, so an avatar set on either platform renders on the other.
 *
 * Mirrors `GroupAvatarImage.swift` from onym-ios PR #164.
 */
object GroupAvatarImage {
    /** Output edge length in pixels. */
    const val SIZE = 256

    /** Raw (pre-base64) byte budget. */
    const val MAX_BYTES = 16 * 1024

    /** Starting JPEG quality (~0.8). The loop steps down until the
     *  encoded size fits [MAX_BYTES]. */
    private const val START_QUALITY = 80
    private const val MIN_QUALITY = 10
    private const val QUALITY_STEP = 10

    /**
     * Centre-crop [source] to a square, scale to [SIZE]×[SIZE], then
     * JPEG-encode under [MAX_BYTES] by stepping quality down from
     * [START_QUALITY]. Returns the smallest encoding that fits the
     * budget, or — if even [MIN_QUALITY] overshoots (vanishingly
     * unlikely for a 256² image) — the [MIN_QUALITY] encoding.
     */
    fun encode(source: Bitmap): ByteArray {
        val square = centreCropSquare(source)
        val scaled = if (square.width == SIZE && square.height == SIZE) {
            square
        } else {
            Bitmap.createScaledBitmap(square, SIZE, SIZE, /* filter = */ true)
        }

        var quality = START_QUALITY
        var encoded = compress(scaled, quality)
        while (encoded.size > MAX_BYTES && quality > MIN_QUALITY) {
            quality -= QUALITY_STEP
            encoded = compress(scaled, quality)
        }
        return encoded
    }

    /**
     * Decode the photo-picker [uri] into a downsampled [Bitmap] and run
     * it through [encode], yielding budget-bounded JPEG bytes ready for
     * [ChatGroup.avatar] / the wire. Returns `null` on any decode
     * failure. Two-pass (bounds first) so a huge source doesn't OOM.
     * Call off the main thread — it does blocking IO + decode.
     */
    fun decodeFromUri(context: Context, uri: Uri): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        val minEdge = min(bounds.outWidth, bounds.outHeight)
        if (minEdge <= 0) return null

        // Downsample so the smaller edge is at least 2× the target —
        // enough detail for the centre-crop + 256² scale without
        // decoding full resolution.
        var sample = 1
        while (minEdge / (sample * 2) >= SIZE * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        return runCatching { encode(bitmap) }.getOrNull()
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /** Crop the largest centred square out of [source]. Returns the
     *  source untouched when it's already square. */
    private fun centreCropSquare(source: Bitmap): Bitmap {
        val edge = min(source.width, source.height)
        if (source.width == edge && source.height == edge) return source
        val x = (source.width - edge) / 2
        val y = (source.height - edge) / 2
        return Bitmap.createBitmap(source, x, y, edge, edge)
    }
}
