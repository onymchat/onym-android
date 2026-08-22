package app.onym.android.design

import android.util.Log
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Brand-anchored QR rendering of [value], with the broken-ring Onym
 * mark badged over the centre.
 *
 * Encoder: ZXing core's [QRCodeWriter], preferring error-correction
 * level **H (~30%)**. The high ECC budget is what makes the centre logo
 * cutout safe — up to ~30% of modules can be obscured (or otherwise
 * unreadable) while the QR still decodes cleanly. We obscure ~22% by
 * area (the white badge), well inside the budget.
 *
 * H also has the smallest payload: 1273 bytes at version 40, and an
 * invite link carrying a group's rules can exceed that (`GroupRules`
 * budgets 1500 bytes of rules for a ~2273-byte link, measured against
 * level M). So the level steps down — H, Q, M, L — until the data fits,
 * and the centre badge is drawn only at H, where the budget covers it.
 * A link too long even for L renders no QR at all rather than throwing:
 * `QRCodeWriter` reports over-capacity data by exception, this runs
 * inside composition, and the share screen's copyable link is still a
 * working way to send an invite. Losing the QR is a worse invite;
 * crashing is no invite.
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
fun OnymQrCode(
    value: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    foreground: Color = Color(0xFF0A0A0C),
    background: Color = Color.White,
) {
    // Null when the value fits no level: the caller's copyable link is
    // still a working invite, and nothing is drawn here.
    val rendering = remember(value) { encodeQrMatrix(value) } ?: return
    val matrix = rendering.matrix
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
        // iOS prototype proportions exactly. Skipped below level H,
        // whose error-correction budget is what makes covering a fifth
        // of the code safe to begin with.
        if (!rendering.badged) return@Box
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

/** A matrix that fits, and whether its level can carry the badge. */
private class QrRendering(val matrix: QrBitMatrix, val badged: Boolean)

/** What [OnymQrCode] can draw for a given value. */
enum class OnymQrFit {
    /** Encodes at level H, whose budget covers the centre badge. */
    BADGED,

    /** Encodes further down the ladder, so the badge is left off. */
    PLAIN,

    /** Fits no level: nothing is drawn, and the caller's copyable link
     *  is the whole invite. */
    NONE,
}

/**
 * What this value would render as — the ladder the composable walks,
 * without walking it.
 *
 * Public so a caller can gate the "scan this" caption on there being
 * something to scan, and so tests can assert against the ladder that
 * ships rather than re-deriving one that can drift from it.
 */
fun onymQrFit(value: String): OnymQrFit = when {
    fitsAtLevel(value, ErrorCorrectionLevel.H) -> OnymQrFit.BADGED
    LOWER_LEVELS.any { fitsAtLevel(value, it) } -> OnymQrFit.PLAIN
    else -> OnymQrFit.NONE
}

private val LOWER_LEVELS = listOf(
    ErrorCorrectionLevel.Q,
    ErrorCorrectionLevel.M,
    ErrorCorrectionLevel.L,
)

private fun fitsAtLevel(value: String, level: ErrorCorrectionLevel): Boolean =
    try {
        QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 1, 1, hintsFor(level))
        true
    } catch (_: Throwable) {
        false
    }

private fun hintsFor(level: ErrorCorrectionLevel): Map<EncodeHintType, Any> = mapOf(
    // Margin = 0 because the Compose surface around the QR provides its
    // own padding (the iOS design wraps the QR in a 12-pt white card,
    // mirrored here).
    EncodeHintType.ERROR_CORRECTION to level,
    EncodeHintType.MARGIN to 0,
    EncodeHintType.CHARACTER_SET to "UTF-8",
)

/**
 * The best level this value fits in, or null if it fits none.
 *
 * Ordered by preference, not by capacity: H first because the centre
 * badge needs its budget, then down until the bytes fit. Only H is
 * badged — obscuring ~22% of a level-M code is how a QR that encodes
 * fine still fails to scan.
 */
private fun encodeQrMatrix(value: String): QrRendering? {
    for (level in listOf(ErrorCorrectionLevel.H) + LOWER_LEVELS) {
        // Pass size 1 → ZXing returns the smallest BitMatrix that
        // satisfies the encoded version's module count. We then scale
        // it ourselves on the canvas, so the input "pixel" size is
        // immaterial.
        val matrix = try {
            QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 1, 1, hintsFor(level))
        } catch (_: Throwable) {
            // Over-capacity for this level (and anything else ZXing
            // refuses). Try the next one down.
            continue
        }
        val dim = matrix.width
        val bits = BooleanArray(dim * dim)
        for (y in 0 until dim) {
            for (x in 0 until dim) {
                bits[y * dim + x] = matrix.get(x, y)
            }
        }
        return QrRendering(
            matrix = QrBitMatrix(dimension = dim, bits = bits),
            badged = level == ErrorCorrectionLevel.H,
        )
    }
    // A QR that silently vanishes is hard to diagnose from a bug
    // report; the caller still has a copyable link.
    Log.w("OnymQrCode", "value of ${value.length} chars fits no correction level; drawing no QR")
    return null
}
