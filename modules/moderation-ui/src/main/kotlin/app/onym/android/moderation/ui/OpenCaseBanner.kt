package app.onym.android.moderation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.onym.android.moderation.CaseNotice

/**
 * "A case is open against you", shown wherever the user already is.
 *
 * Non-blocking by requirement, not by preference: a case-open mark is
 * procedural state and the profile forbids degrading service on it
 * beyond displaying the case's existence (Moderation.md §5.5). So this
 * is a banner, never a gate — the iOS `OpenCaseBanner`, which sits both
 * on the root view and in Settings → Moderation.
 *
 * Being told is the whole point. Before this, an open case reached the
 * client (the gate carries the notices on an otherwise-operational
 * answer) and was rendered nowhere the user would look, so the first
 * they knew of a case was the ban at the end of it.
 */
@Composable
fun OpenCaseBanner(
    notices: List<CaseNotice>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
            .testTag("moderation.case_banner"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = pluralStringResource(
                    R.plurals.moderation_case_banner,
                    notices.size,
                    notices.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
