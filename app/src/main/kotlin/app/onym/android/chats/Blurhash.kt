package app.onym.android.chats

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Minimal BlurHash (woltapp/blurhash) encoder + decoder. Android twin
 * of iOS `Blurhash`. Chat images ship a BlurHash string (not an inline
 * thumbnail) so the bubble renders a colour placeholder while the real
 * blob downloads + decrypts.
 */
object Blurhash {
    private val alphabet =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    fun encode(bitmap: Bitmap, xComponents: Int = 4, yComponents: Int = 3): String? {
        val xc = xComponents.coerceIn(1, 9)
        val yc = yComponents.coerceIn(1, 9)
        val w = 32
        val h = 32
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val factors = ArrayList<DoubleArray>(xc * yc)
        for (j in 0 until yc) {
            for (i in 0 until xc) {
                val norm = if (i == 0 && j == 0) 1.0 else 2.0
                var r = 0.0; var g = 0.0; var b = 0.0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val basis = norm *
                            cos(PI * i * x / w) * cos(PI * j * y / h)
                        val px = pixels[y * w + x]
                        r += basis * sRGBToLinear(Color.red(px))
                        g += basis * sRGBToLinear(Color.green(px))
                        b += basis * sRGBToLinear(Color.blue(px))
                    }
                }
                val scale = 1.0 / (w * h)
                factors.add(doubleArrayOf(r * scale, g * scale, b * scale))
            }
        }

        val dc = factors[0]
        val ac = factors.drop(1)
        val sb = StringBuilder()
        sb.append(encode83(xc - 1 + (yc - 1) * 9, 1))

        val maxAc = ac.flatMap { it.toList() }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        val quantMax = if (ac.isEmpty()) 0 else (maxAc * 166 - 0.5).toInt().coerceIn(0, 82)
        sb.append(encode83(quantMax, 1))
        val actualMax = if (ac.isEmpty()) 1.0 else (quantMax + 1) / 166.0

        sb.append(encode83(encodeDC(dc), 4))
        for (comp in ac) sb.append(encode83(encodeAC(comp, actualMax), 2))
        return sb.toString()
    }

    fun decode(hash: String, width: Int, height: Int, punch: Double = 1.0): Bitmap? {
        if (hash.length < 6) return null
        val sizeFlag = decode83(hash.substring(0, 1))
        val yc = sizeFlag / 9 + 1
        val xc = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * xc * yc) return null
        val quantMax = decode83(hash.substring(1, 2))
        val maxValue = (quantMax + 1) / 166.0 * punch

        val colors = Array(xc * yc) { DoubleArray(3) }
        colors[0] = decodeDC(decode83(hash.substring(2, 6)))
        for (i in 1 until xc * yc) {
            val from = 4 + i * 2
            colors[i] = decodeAC(decode83(hash.substring(from, from + 2)), maxValue)
        }

        val w = max(1, width); val h = max(1, height)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (j in 0 until yc) {
                    for (i in 0 until xc) {
                        val basis = cos(PI * x * i / w) * cos(PI * y * j / h)
                        val c = colors[i + j * xc]
                        r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
                    }
                }
                pixels[y * w + x] = Color.rgb(linearToSRGB(r), linearToSRGB(g), linearToSRGB(b))
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ─── base83 + colour helpers ──────────────────────────────────

    private fun encode83(value: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            sb.append(alphabet[digit])
        }
        return sb.toString()
    }

    private fun decode83(s: String): Int = s.fold(0) { acc, c -> acc * 83 + alphabet.indexOf(c) }

    private fun sRGBToLinear(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSRGB(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        val s = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }

    private fun encodeDC(c: DoubleArray): Int {
        val r = linearToSRGB(c[0]); val g = linearToSRGB(c[1]); val b = linearToSRGB(c[2])
        return (r shl 16) + (g shl 8) + b
    }

    private fun decodeDC(value: Int): DoubleArray = doubleArrayOf(
        sRGBToLinear((value shr 16) and 255),
        sRGBToLinear((value shr 8) and 255),
        sRGBToLinear(value and 255),
    )

    private fun encodeAC(c: DoubleArray, maxValue: Double): Int {
        fun q(v: Double): Int =
            (floor(signPow(v / maxValue, 0.5) * 9 + 9.5)).toInt().coerceIn(0, 18)
        return q(c[0]) * 19 * 19 + q(c[1]) * 19 + q(c[2])
    }

    private fun decodeAC(value: Int, maxValue: Double): DoubleArray {
        val r = value / (19 * 19); val g = (value / 19) % 19; val b = value % 19
        fun d(q: Int): Double = signPow((q - 9) / 9.0, 2.0) * maxValue
        return doubleArrayOf(d(r), d(g), d(b))
    }

    private fun signPow(v: Double, exp: Double): Double = sign(v) * kotlin.math.abs(v).pow(exp)
}
