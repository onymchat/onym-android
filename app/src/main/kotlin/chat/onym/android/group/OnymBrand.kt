package chat.onym.android.group

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Per-theme design-token bundle for the Create Group flow. Two
 * pre-built variants live in the companion ([Light] / [Dark]); the
 * active set is provided at the root by [OnymTheme] and read by
 * descendant Composables via `LocalOnymTokens.current.X`.
 *
 * Lifted from the design's `app.jsx` `THEMES.{light,dark}` block —
 * same source iOS used. Mirrors `OnymTokens` in `OnymBrand.swift`
 * from onym-ios PR #31 (the rewrite that flipped from a static
 * dark-only palette to the dual data class).
 */
@Immutable
data class OnymTokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val green: Color,
    val red: Color,
    /** Reads on accent fills (button labels, the tyranny crown's
     *  centre pip, etc.). White on light, black on dark. */
    val onAccent: Color,
) {
    companion object {
        val Light: OnymTokens = OnymTokens(
            bg              = Color(0xFFFFFFFF),
            surface         = Color(0xFFF5F5F7),
            surface2        = Color(0xFFFFFFFF),
            surface3        = Color(0xFFEBEBEF),
            text            = Color(0xFF0A0A0C),
            text2           = Color(0xFF0A0A0C).copy(alpha = 0.62f),
            text3           = Color(0xFF0A0A0C).copy(alpha = 0.42f),
            hairline        = Color.Black.copy(alpha = 0.06f),
            hairlineStrong  = Color.Black.copy(alpha = 0.12f),
            green           = Color(0xFF1FA84A),
            red             = Color(0xFFE5392E),
            onAccent        = Color.White,
        )

        val Dark: OnymTokens = OnymTokens(
            bg              = Color(0xFF000000),
            surface         = Color(0xFF0E0E10),
            surface2        = Color(0xFF17171A),
            surface3        = Color(0xFF1F1F23),
            text            = Color(0xFFF2F2F4),
            text2           = Color(0xFFF2F2F4).copy(alpha = 0.62f),
            text3           = Color(0xFFF2F2F4).copy(alpha = 0.40f),
            hairline        = Color.White.copy(alpha = 0.07f),
            hairlineStrong  = Color.White.copy(alpha = 0.12f),
            green           = Color(0xFF34C759),
            red             = Color(0xFFFF453A),
            onAccent        = Color.Black,
        )
    }
}

/**
 * CompositionLocal carrying the active [OnymTokens] set. Defaults
 * to [OnymTokens.Dark] so any descendant rendered outside an
 * [OnymTheme] scope still has a sensible value (and matches the
 * pre-PR-31 dark-only behaviour).
 *
 * `staticCompositionLocalOf` (rather than `compositionLocalOf`) is
 * correct here — the swap on theme change happens via the root
 * [OnymTheme]'s `CompositionLocalProvider` re-providing, not via
 * fine-grained recomposition tracking.
 */
val LocalOnymTokens = staticCompositionLocalOf { OnymTokens.Dark }

// ─── Accent palette ──────────────────────────────────────────────

/**
 * The six accent colours the Step1 picker offers. Each carries both
 * a [light] (darker, desaturated for legibility on white surfaces)
 * and [dark] (saturated, bright for dark surfaces) variant; the
 * active variant is resolved per-call by [color] based on the
 * provided [LocalOnymTokens] (so a forced theme override propagates
 * the same way a system theme change does).
 *
 * Light/dark hex values lifted from the design's `app.jsx`
 * `THEMES.{light,dark}.accents` blocks. Mirrors `OnymAccent` from
 * onym-ios PR #31.
 */
enum class OnymAccent(private val light: Color, private val dark: Color) {
    Orange(Color(0xFFE85F2A), Color(0xFFFF7A45)),
    Blue  (Color(0xFF1F86E0), Color(0xFF3FA8FF)),
    Green (Color(0xFF1FA84A), Color(0xFF3DD66E)),
    Purple(Color(0xFF8B4DEB), Color(0xFFB278FF)),
    Pink  (Color(0xFFE03253), Color(0xFFFF4D6D)),
    Yellow(Color(0xFFD9A400), Color(0xFFFFC93C)),
    ;

    /** Resolve the active variant against the current
     *  [LocalOnymTokens]. Identity-checking against
     *  [OnymTokens.Dark] (rather than [isSystemInDarkTheme]) lets a
     *  forced-theme override (Settings toggle, Compose preview)
     *  propagate without an extra branch. */
    @Composable
    @ReadOnlyComposable
    fun color(): Color =
        if (LocalOnymTokens.current === OnymTokens.Dark) dark else light
}

// ─── Theme scope ─────────────────────────────────────────────────

/**
 * Root scope for the Create Group flow. Provides [LocalOnymTokens]
 * and a matching Material3 `colorScheme` so any nested
 * `MaterialTheme`-aware widget (TextField cursor, ripple, etc) also
 * reads the right surface family.
 *
 * Wrap the create-group nav route in this; Chats / Settings tabs
 * already adapt via Material/system colours and don't need it.
 *
 * Mirrors the SwiftUI `.preferredColorScheme(_:)` plumbing that iOS
 * PR #31 added at the `CreateGroupView` root.
 */
@Composable
fun OnymTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) OnymTokens.Dark else OnymTokens.Light
    CompositionLocalProvider(LocalOnymTokens provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            content = content,
        )
    }
}

// ─── OnymMark — broken-ring brand logo ───────────────────────────

/** Rotation that lands the brand mark's two gaps at clock positions
 *  1:00 and 7:00. See [OnymMark]'s "Gap orientation" doc for the
 *  derivation. */
private const val GAP_ORIENTATION_DEG: Float = -52.8f


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
 *
 * **Gap orientation.** The pre-rotation arc geometry (startAngle 0°
 * + 180°, each sweeping 165.6° clockwise) places the two ~14.4° gaps
 * symmetrically at canvas angles 352.8° and 172.8° — i.e. just above
 * the 3 o'clock and 9 o'clock points. We then apply a fixed rotation
 * to swing the gaps to the brand-canonical positions: **1 o'clock**
 * (canvas 300°) and **7 o'clock** (canvas 120°). The required offset
 * is `300° − 352.8° = −52.8°`. Don't change this without coordinating
 * with iOS — the brand mark must read identical across platforms.
 */
@Composable
fun OnymMark(
    size: Dp,
    color: Color = LocalOnymTokens.current.text,
    strokeRatio: Float = 0.16f,
    spinning: Boolean = false,
    fillOpacity: Float = 0.92f,
) {
    val rotation = remember { Animatable(GAP_ORIENTATION_DEG) }
    LaunchedEffect(spinning) {
        if (spinning) {
            rotation.animateTo(
                targetValue = GAP_ORIENTATION_DEG + 360f,
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
    accent: Color = OnymAccent.Blue.color(),
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
            color = if (brand) accent else LocalOnymTokens.current.text,
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
