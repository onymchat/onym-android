package chat.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared layout atoms for the redesigned Settings flow. Keep them
 * private-to-the-package so the rest of the app can't import the
 * Apple-Settings-style row by accident — each screen wants the
 * specific spacing tokens these atoms encode.
 */

internal object SettingsTile {
    val Purple = Color(0xFFA04CE0)
    val Blue = Color(0xFF0A84FF)
    val Indigo = Color(0xFF5B5BE2)
    val Orange = Color(0xFFFF7A2D)
    val Green = Color(0xFF30B45A)
    val Gray = Color(0xFF8E8E93)
    val Red = Color(0xFFE5392E)
    val Teal = Color(0xFF2BB3CF)
    val Amber = Color(0xFFFF9500)
    val GitHub = Color(0xFF0A0A0C)
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { content() }
}

@Composable
internal fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SettingsFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingsHairline(insetStart: Dp = 60.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = insetStart)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
internal fun SettingsTileBox(
    icon: ImageVector,
    background: Color,
    size: Dp = 30.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
internal fun SettingsTileLabel(
    label: String,
    background: Color,
    size: Dp = 30.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/**
 * Apple-Settings-style grouped row. Single tile glyph + title + optional
 * subtitle; trailing accessory is right-aligned. The trailing chevron
 * is drawn automatically when [onClick] is non-null and [showChevron]
 * is true.
 */
@Composable
internal fun SettingsRow(
    leading: (@Composable () -> Unit)? = null,
    title: String,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    isLast: Boolean = false,
    insetHairline: Dp = 60.dp,
    modifier: Modifier = Modifier,
) {
    val rowMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(rowMod)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        style = if (subtitleMono) {
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (!isLast) SettingsHairline(insetStart = insetHairline)
    }
}

/**
 * Six-byte hex of a public key, with the universal "BLS …" prefix
 * stripped if present.
 */
internal fun shortHex(bytes: ByteArray, keepBytes: Int = 9): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder()
    val n = minOf(keepBytes, bytes.size)
    for (i in 0 until n) sb.append("%02x".format(bytes[i].toInt() and 0xFF))
    if (bytes.size > n) sb.append('…')
    return sb.toString()
}

/** Long-form mono fragment: 18 hex chars + ellipsis, suitable for
 *  the identity hero. */
internal fun heroHex(bytes: ByteArray): String = shortHex(bytes, keepBytes = 9)
