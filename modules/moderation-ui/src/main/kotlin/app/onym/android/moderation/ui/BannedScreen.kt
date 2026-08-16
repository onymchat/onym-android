package app.onym.android.moderation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.moderation.BanState

/**
 * The full-screen ban gate. A silent brick is nonconforming (profile
 * §5.2 item 3): at least one route out — the appeal URL, the
 * new-holder claim, or the authority's contact — always renders.
 */
@Composable
fun BannedScreen(
    state: BanState,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Full-screen on RootScreen's early-return path: no
                // inset-applying Scaffold above, so the safe area is
                // this screen's own job (see OnboardingScaffold).
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("moderation.banned"),
        ) {
            Text(
                text = stringResource(R.string.moderation_banned_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.banExpires?.let {
                    stringResource(R.string.moderation_banned_expires, formatExpiry(it))
                } ?: stringResource(R.string.moderation_banned_permanent),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.moderation_banned_verdict_ref, state.verdictRef),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.moderation_banned_contact,
                    state.authorityContact,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            // Backend-supplied URLs pass the same scheme allowlist as
            // the contact: this screen is otherwise fully locked down,
            // and handing a non-http scheme to ACTION_VIEW would turn
            // a served field into an arbitrary implicit intent.
            val appealUri = state.appealUrl?.let(::safeActionUri)
            val newHolderUri = state.newHolderUrl?.let(::safeActionUri)
            appealUri?.let { url ->
                Button(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("moderation.banned.appeal"),
                ) { Text(stringResource(R.string.moderation_banned_appeal)) }
                Spacer(Modifier.height(8.dp))
            }
            newHolderUri?.let { url ->
                OutlinedButton(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("moderation.banned.newHolder"),
                ) { Text(stringResource(R.string.moderation_banned_new_holder)) }
            }
            if (appealUri == null && newHolderUri == null) {
                // With no declared routes, the contact itself becomes
                // the tappable route out when it parses as one — a
                // screen with only untappable text is a step away from
                // the silent brick §5.2 forbids.
                safeActionUri(state.authorityContact)?.let { uri ->
                    OutlinedButton(
                        onClick = { onOpenUrl(uri) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moderation.banned.contactAction"),
                    ) { Text(stringResource(R.string.moderation_banned_contact_action)) }
                }
            }
        }
    }
}

/**
 * A served value as an openable URI, scheme-allowlisted: http(s) and
 * `mailto:` verbatim, a bare email as `mailto:`, anything else null.
 * Applied to EVERY backend-supplied field this screen can hand to
 * ACTION_VIEW — appeal, new-holder, and the contact alike.
 */
internal fun safeActionUri(raw: String): String? {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        trimmed.startsWith("mailto:") -> trimmed
        Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(trimmed) -> "mailto:$trimmed"
        else -> null
    }
}

/**
 * The verdict's RFC 3339 expiry, rendered for the reader's locale and
 * zone — `2026-09-08T00:00:00Z` is wire format, not user copy. An
 * unparseable value falls back to the raw string: showing the wire
 * form beats hiding when the ban ends.
 */
private fun formatExpiry(rfc3339: String): String = try {
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
        .withLocale(java.util.Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(rfc3339))
} catch (_: Exception) {
    rfc3339
}
