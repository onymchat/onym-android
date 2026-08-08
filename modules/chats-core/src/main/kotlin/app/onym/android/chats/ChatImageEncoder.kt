package app.onym.android.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Downscale → JPEG-budget → BlurHash pipeline for a picked image, so
 * what we encrypt + upload to Blossom is a sensibly-sized display image
 * and the message carries a placeholder. Android twin of iOS
 * `ChatImageEncoder`.
 */
object ChatImageEncoder {
    const val MAX_EDGE = 2048
    const val MAX_BYTES = 2 * 1024 * 1024

    data class Encoded(val jpeg: ByteArray, val width: Int, val height: Int, val blurhash: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Encoded) return false
            return jpeg.contentEquals(other.jpeg) &&
                width == other.width && height == other.height && blurhash == other.blurhash
        }
        override fun hashCode(): Int =
            31 * (31 * (31 * jpeg.contentHashCode() + width) + height) + blurhash.hashCode()
    }

    fun encode(imageData: ByteArray): Encoded? {
        val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return null
        return encode(decoded)
    }

    fun encode(bitmap: Bitmap): Encoded? {
        val scaled = downscale(bitmap, MAX_EDGE)
        var quality = 85
        var jpeg = compress(scaled, quality)
        while (jpeg.size > MAX_BYTES && quality > 40) {
            quality -= 10
            jpeg = compress(scaled, quality)
        }
        val blurhash = Blurhash.encode(scaled) ?: ""
        return Encoded(jpeg = jpeg, width = scaled.width, height = scaled.height, blurhash = blurhash)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val factor = maxEdge.toDouble() / longest
        val w = (bitmap.width * factor).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * factor).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
