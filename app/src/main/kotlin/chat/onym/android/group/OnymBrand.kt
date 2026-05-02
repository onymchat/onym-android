package chat.onym.android.group

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// ─── Tokens ──────────────────────────────────────────────────────

/**
 * Dark-theme design tokens for the Create Group flow. Mirrors
 * `OnymTokens` in `OnymBrand.swift` from onym-ios PR #26. Light
 * theme will land later; PR-C ships dark only.
 */
object OnymTokens {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0E0E10)
    val Surface2 = Color(0xFF17171A)
    val Surface3 = Color(0xFF1F1F23)
    val Text = Color(0xFFF2F2F4)
    val Text2 = Color(0xFFF2F2F4).copy(alpha = 0.62f)
    val Text3 = Color(0xFFF2F2F4).copy(alpha = 0.40f)
    val Hairline = Color.White.copy(alpha = 0.07f)
    val HairlineStrong = Color.White.copy(alpha = 0.12f)
    val Green = Color(0xFF34C759)
    val Red = Color(0xFFFF453A)

    /** Reads on accent fills (button labels, etc.). */
    val OnAccent: Color = Color.Black
}

// ─── Accent palette ──────────────────────────────────────────────

/**
 * The six accent colours the Step1 picker offers. Mirrors
 * `OnymAccent` from onym-ios PR #26.
 */
enum class OnymAccent(val color: Color) {
    Orange(Color(0xFFFF7A45)),
    Blue(Color(0xFF3FA8FF)),
    Green(Color(0xFF3DD66E)),
    Purple(Color(0xFFB278FF)),
    Pink(Color(0xFFFF4D6D)),
    Yellow(Color(0xFFFFC93C)),
}

// ─── OnymMark — broken-ring brand logo ───────────────────────────

/**
 * The Onym brand mark: a broken/segmented ring with two narrow
 * radial gaps. The gaps suggest privacy/anonymity — the identity is
 * whole but never fully closed.
 *
 * Implemented as two arcs (each 165.6° = 46% of 360°) with butt
 * caps, separated by 4% gaps. SwiftUI uses a single dashed stroke;
 * Compose's `drawArc` is the most direct equivalent on Android.
 *
 * Mirrors `OnymMark` from onym-ios PR #26.
 */
@Composable
fun OnymMark(
    size: Dp,
    color: Color = OnymTokens.Text,
    strokeRatio: Float = 0.16f,
    spinning: Boolean = false,
    fillOpacity: Float = 0.92f,
) {
    val rotation = remember { Animatable(-90f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            rotation.animateTo(
                targetValue = -90f + 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
    }
    Canvas(
        modifier = Modifier
            .size(size)
            .rotate(rotation.value),
    ) {
        val px = this.size.minDimension
        val stroke = px * strokeRatio
        val arcSweep = 165.6f
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        val arcSize = Size(px - stroke, px - stroke)
        val style = Stroke(width = stroke, cap = StrokeCap.Butt)
        drawArc(
            color = color.copy(alpha = fillOpacity),
            startAngle = 0f,
            sweepAngle = arcSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = style,
        )
        drawArc(
            color = color.copy(alpha = fillOpacity),
            startAngle = 180f,
            sweepAngle = arcSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = style,
        )
    }
}

// ─── Group avatar ────────────────────────────────────────────────

/**
 * Avatar slot. The user never uploads an image in this prototype, so
 * the slot always shows the brand mark — same behaviour as the
 * iOS twin's `OnymGroupAvatar`.
 */
@Composable
fun OnymGroupAvatar(
    size: Dp,
    accent: Color = OnymAccent.Blue.color,
    spinning: Boolean = false,
    /** When `true` the mark renders in the accent colour rather than
     *  the neutral text colour — used on the Creating screen. */
    brand: Boolean = false,
) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        OnymMark(
            size = size,
            color = if (brand) accent else OnymTokens.Text,
            spinning = spinning,
            fillOpacity = if (brand) 1.0f else 0.92f,
        )
    }
}

// ─── Governance icons ────────────────────────────────────────────

/**
 * Small badge icons that go on each governance card. Three distinct
 * silhouettes — crown (admin), facing bubbles (dialog), nodes-in-ring
 * (anarchy) — drawn with `Canvas` paths rather than Material icons
 * because the design uses custom artwork.
 *
 * Mirrors `OnymGovIcon` from onym-ios PR #26.
 */
@Composable
fun OnymGovIcon(
    type: OnymUIGovernance,
    accent: Color,
    size: Dp = 44.dp,
    dimmed: Boolean = false,
) {
    val strokeColor = if (dimmed) OnymTokens.Text3 else accent
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(strokeColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.minDimension
            when (type) {
                OnymUIGovernance.Tyranny -> drawTyrannyMark(s, strokeColor, dimmed)
                OnymUIGovernance.OneOnOne -> drawDialogMark(s, strokeColor)
                OnymUIGovernance.Anarchy -> drawAnarchyMark(s, strokeColor)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTyrannyMark(
    s: Float,
    color: Color,
    dimmed: Boolean,
) {
    // Crown polygon points — same proportions as iOS (each /44 of
    // canvas), reframed against the `s × s` Compose canvas.
    val path = Path().apply {
        moveTo(s * 13f / 44f, s * 24f / 44f)
        lineTo(s * 15f / 44f, s * 17f / 44f)
        lineTo(s * 19f / 44f, s * 21f / 44f)
        lineTo(s * 22f / 44f, s * 15f / 44f)
        lineTo(s * 25f / 44f, s * 21f / 44f)
        lineTo(s * 29f / 44f, s * 17f / 44f)
        lineTo(s * 31f / 44f, s * 24f / 44f)
        close()
    }
    drawPath(path, color)
    // Bar under the crown.
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 13f / 44f, s * 25f / 44f),
        size = Size(s * 18f / 44f, s * 3f / 44f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.8f / 44f),
    )
    // Center dot.
    drawCircle(
        color = if (dimmed) OnymTokens.Text3 else Color.White,
        radius = s * 1.2f / 44f,
        center = Offset(s * 22f / 44f, s * 20f / 44f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDialogMark(
    s: Float,
    color: Color,
) {
    // Two simple speech-bubble blobs. Compose port favours
    // simplicity over the exact iOS quad-curve geometry — both
    // platforms render two facing bubbles in the same approximate
    // location.
    val left = Path().apply {
        moveTo(s * 9f / 44f, s * 17f / 44f)
        lineTo(s * 22f / 44f, s * 17f / 44f)
        lineTo(s * 22f / 44f, s * 23f / 44f)
        lineTo(s * 13f / 44f, s * 23f / 44f)
        lineTo(s * 13f / 44f, s * 26f / 44f)
        lineTo(s * 9f / 44f, s * 23f / 44f)
        close()
    }
    drawPath(left, color)
    val right = Path().apply {
        moveTo(s * 22f / 44f, s * 22f / 44f)
        lineTo(s * 35f / 44f, s * 22f / 44f)
        lineTo(s * 35f / 44f, s * 28f / 44f)
        lineTo(s * 31f / 44f, s * 28f / 44f)
        lineTo(s * 31f / 44f, s * 31f / 44f)
        lineTo(s * 27f / 44f, s * 28f / 44f)
        lineTo(s * 22f / 44f, s * 28f / 44f)
        close()
    }
    drawPath(right, color.copy(alpha = 0.55f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnarchyMark(
    s: Float,
    color: Color,
) {
    // Five nodes in a ring with light edges between every pair —
    // the "everyone is equal" suggestion.
    val count = 5
    val nodes = (0 until count).map { i ->
        val angle = (i.toFloat() / count.toFloat()) * (2 * Math.PI.toFloat()) - (Math.PI.toFloat() / 2f)
        Offset(
            x = s * 22f / 44f + cos(angle) * s * 10f / 44f,
            y = s * 22f / 44f + sin(angle) * s * 10f / 44f,
        )
    }
    // Edges first so nodes paint over them.
    val edgeStroke = Stroke(width = s * 0.9f / 44f)
    for (i in 0 until count) {
        for (j in (i + 1) until count) {
            drawLine(
                color = color.copy(alpha = 0.45f),
                start = nodes[i],
                end = nodes[j],
                strokeWidth = edgeStroke.width,
            )
        }
    }
    for (n in nodes) {
        drawCircle(color = color, radius = s * 2.6f / 44f, center = n)
    }
}
