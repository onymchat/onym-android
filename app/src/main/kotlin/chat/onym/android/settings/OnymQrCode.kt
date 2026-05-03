package chat.onym.android.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.onym.android.group.OnymMark
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Brand-anchored QR rendering of [value], with the broken-ring Onym
 * mark badged over the centre.
 *
 * Encoder: ZXing core's [QRCodeWriter] at error-correction level
 * **H (~30%)**. The high ECC budget is what makes the centre logo
 * cutout safe — up to ~30% of modules can be obscured (or otherwise
 * unreadable) while the QR still decodes cleanly. We obscure ~22% by
 * area (the white badge), well inside the budget.
 *
 * Renderer: hand-drawn on a Compose [Canvas] so we can match the
 * iOS prototype's chunky **rounded modules** + white centre badge
 * with the [OnymMark] inside. ZXing's stock `MatrixToImageWriter`
 * would give us a hard-pixel raster bitmap — wrong feel and an extra
 * bitmap allocation per recomposition.
 *
 * The bit matrix is memoised on [value]; the draw block is pure (it
 * only reads the memoised matrix + the Compose-resolved colours), so
 * recompositions on layout-only changes don't re-encode.
 *
 * Mirrors the iOS design's `QRCode` component (`settings.jsx` lines
 * 414–474) — same finder-corner shape, same centre badge, same
 * rounded modules. The Android port differs only in the encoder
 * (real QR via ZXing; iOS used a deterministic visual placeholder).
 */
@Composable
internal fun OnymQrCode(
    value: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    foreground: Color = Color(0xFF0A0A0C),
    background: Color = Color.White,
) {
    val matrix = remember(value) { encodeQrMatrix(value) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val px = this.size.minDimension
            val cell = px / matrix.dimension
            // Inset each module very slightly so adjacent rounded
            // squares don't visually merge into a single blob.
            val inset = cell * 0.04f
            val cornerRadius = CornerRadius(cell * 0.18f)
            for (y in 0 until matrix.dimension) {
                for (x in 0 until matrix.dimension) {
                    if (!matrix.isOn(x, y)) continue
                    drawRoundRect(
                        color = foreground,
                        topLeft = Offset(x * cell + inset, y * cell + inset),
                        size = Size(cell - 2 * inset, cell - 2 * inset),
                        cornerRadius = cornerRadius,
                    )
                }
            }
        }
        // Centre badge — ~22% of the QR side. White rounded square,
        // OnymMark sits on top in the foreground colour. Matches the
        // iOS prototype proportions exactly.
        val badgeSize = size * 0.22f
        Box(
            modifier = Modifier
                .size(badgeSize)
                .clip(RoundedCornerShape(badgeSize * 0.22f))
                .background(background)
                .padding(badgeSize * 0.13f),
            contentAlignment = Alignment.Center,
        ) {
            OnymMark(
                size = badgeSize * 0.74f,
                color = foreground,
            )
        }
    }
}

/**
 * Bit-grid view of a ZXing [com.google.zxing.common.BitMatrix]. We
 * don't pass the matrix around directly so the call site doesn't
 * pull in ZXing types and so we can cache a small int instead of
 * holding the whole matrix's internal long-array.
 */
private class QrBitMatrix(
    val dimension: Int,
    private val bits: BooleanArray,
) {
    fun isOn(x: Int, y: Int): Boolean = bits[y * dimension + x]
}

private fun encodeQrMatrix(value: String): QrBitMatrix {
    // Hint the highest error-correction level so the centre logo
    // cutout doesn't break decode. Margin = 0 because the Compose
    // surface around the QR provides its own padding (the iOS
    // design wraps the QR in a 12-pt white card, mirrored here).
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Pass size 1 → ZXing returns the smallest BitMatrix that
    // satisfies the encoded version's module count. We then scale
    // it ourselves on the canvas, so the input "pixel" size is
    // immaterial.
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 1, 1, hints)
    val dim = matrix.width
    val bits = BooleanArray(dim * dim)
    for (y in 0 until dim) {
        for (x in 0 until dim) {
            bits[y * dim + x] = matrix.get(x, y)
        }
    }
    return QrBitMatrix(dimension = dim, bits = bits)
}
