package app.onym.android.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX [ImageAnalysis.Analyzer] that decodes a QR code out of each
 * camera frame with zxing's [MultiFormatReader] and reports the first
 * decoded text via [onDecoded].
 *
 * Decoding runs on the analyzer's executor (a background thread), so
 * [onDecoded] is invoked off the main thread — the caller
 * ([QrScannerScreen]) marshals back and de-dupes. The analyzer itself
 * is stateless beyond the reused reader and keeps firing until the
 * caller stops delivering frames; a clean QR typically resolves
 * within a frame or two.
 *
 * We feed the Y (luminance) plane straight into
 * [PlanarYUVLuminanceSource] using its row stride as the data width —
 * no Bitmap allocation, no colour conversion. Restricted to
 * [BarcodeFormat.QR_CODE] so the reader doesn't waste cycles probing
 * 1-D symbologies we never emit.
 */
class QrCodeAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val text = decode(image)
            if (text != null && text.isNotEmpty()) onDecoded(text)
        } finally {
            // Must always close, or CameraX stalls the analysis pipe.
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val source = PlanarYUVLuminanceSource(
            /* yuvData = */ data,
            /* dataWidth = */ plane.rowStride,
            /* dataHeight = */ image.height,
            /* left = */ 0,
            /* top = */ 0,
            /* width = */ image.width,
            /* height = */ image.height,
            /* reverseHorizontal = */ false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            // No QR in this frame — also try the inverted luminance so
            // light-on-dark codes (rare, but some sharers theme them)
            // still resolve.
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (_: NotFoundException) {
                null
            }
        } finally {
            reader.reset()
        }
    }
}
