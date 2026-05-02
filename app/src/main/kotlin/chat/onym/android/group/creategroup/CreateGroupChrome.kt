package chat.onym.android.group.creategroup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.onym.android.group.OnymTokens

/**
 * Centered title block used at the top of every screen
 * (title-only nav per the design — back/cancel live in the footer).
 *
 * Mirrors `OnymNavTitle` in `CreateGroupView.swift` from onym-ios PR #26.
 */
@Composable
internal fun OnymNavTitle(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = OnymTokens.Text,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = OnymTokens.Text3,
                style = TextStyle(fontSize = 11.sp),
            )
        }
    }
}

/** All-caps, tracked section label inside a screen body. */
@Composable
internal fun OnymSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = OnymTokens.Text3,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.88.sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp),
    )
}

/**
 * Primary footer button (52dp tall, full-width, accent fill). Has a
 * disabled state that swaps fill + text colour to the surface3
 * tokens.
 */
@Composable
internal fun OnymPrimaryButton(
    title: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (enabled) accent else OnymTokens.Surface3
    val fg = if (enabled) OnymTokens.OnAccent else OnymTokens.Text3
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = fg,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.16).sp,
            ),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** Quiet text-only button shown under the primary CTA (Cancel / Back). */
@Composable
internal fun OnymQuietButton(
    title: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = OnymTokens.Text2,
            style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Card (surface2 fill, hairline border, rounded). Default 14dp
 *  radius matches the design's primary card shape. */
@Composable
internal fun OnymCard(
    radius: Int = 14,
    fill: Color = OnymTokens.Surface2,
    borderColor: Color = OnymTokens.Hairline,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.dp))
            .background(fill)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(radius.dp)),
    ) { content() }
}
