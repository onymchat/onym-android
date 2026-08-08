package app.onym.android.group

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.onym.android.design.LocalOnymTokens
import kotlin.math.cos
import kotlin.math.sin

// ─── Governance icons ────────────────────────────────────────────

/**
 * Small badge icons that go on each governance card. Three distinct
 * silhouettes — crown (admin), facing bubbles (dialog), nodes-in-ring
 * (anarchy) — drawn with `Canvas` paths rather than Material icons
 * because the design uses custom artwork.
 *
 * Lives in `group/` (not `design/`) because it renders per
 * [OnymUIGovernance] — the SEP-governance bridge type — while the
 * pure brand tokens/marks live in `app.onym.android.design`.
 *
 * Mirrors `OnymGovIcon` from onym-ios PR #26.
 */
@Composable
internal fun OnymGovIcon(
    type: OnymUIGovernance,
    accent: Color,
    size: Dp = 44.dp,
    dimmed: Boolean = false,
) {
    // Resolve theme-dependent colours in the @Composable parent so
    // the DrawScope helpers below stay pure (no @Composable scope
    // inside Canvas's draw block).
    val tokens = LocalOnymTokens.current
    val strokeColor = if (dimmed) tokens.text3 else accent
    val pipColor = if (dimmed) tokens.text3 else tokens.onAccent
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
                OnymUIGovernance.Tyranny -> drawTyrannyMark(s, strokeColor, pipColor)
                OnymUIGovernance.OneOnOne -> drawDialogMark(s, strokeColor)
                OnymUIGovernance.Anarchy -> drawAnarchyMark(s, strokeColor)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTyrannyMark(
    s: Float,
    color: Color,
    pipColor: Color,
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
    // Center dot — themed via [pipColor] (onAccent in normal state,
    // text3 when dimmed). Was hardcoded `Color.White` pre-PR-31.
    drawCircle(
        color = pipColor,
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
