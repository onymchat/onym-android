package app.onym.android.moderation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.onym.android.moderation.MandateRecord
import kotlinx.coroutines.launch

/**
 * The consent surface: the reviewed manifest snapshot rendered from
 * its exact retained bytes, an Agree that consents with exactly that
 * snapshot, and the Unavailable path (nothing to consent to — the
 * onboarding step reports `Unavailable`, never a silent skip).
 *
 * Hosted in two places with the same body: the onboarding Moderation
 * step, and the post-onboarding full-screen `NeedsConsent` /
 * re-consent gate.
 */
@Composable
fun ModerationConsentContent(
    controller: ModerationConsentController,
    onConsented: (MandateRecord) -> Unit,
    onUnavailableContinue: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by controller.snapshots.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(controller) {
        if (state is ModerationConsentController.UiState.Loading) controller.load()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("moderation.consent"),
    ) {
        when (val current = state) {
            is ModerationConsentController.UiState.Loading,
            is ModerationConsentController.UiState.Consenting,
            -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.moderation_consent_loading))
                }
            }

            is ModerationConsentController.UiState.Unavailable -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.moderation_consent_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { scope.launch { controller.load() } },
                        modifier = Modifier.testTag("moderation.consent.retry"),
                    ) { Text(stringResource(R.string.moderation_consent_retry)) }
                    if (onUnavailableContinue != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onUnavailableContinue,
                            modifier = Modifier.testTag("moderation.consent.continue"),
                        ) { Text(stringResource(R.string.moderation_consent_continue)) }
                    }
                }
            }

            is ModerationConsentController.UiState.Review -> {
                Text(
                    text = stringResource(R.string.moderation_consent_title, current.listing.name),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.moderation_consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = current.termsDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    // Bounded, not weighted: the onboarding step hosts
                    // this inside a scrolling Column, where a weighted
                    // child measures against an unbounded max and
                    // collapses to ZERO height — the terms invisibly
                    // "reviewed" while Agree still rendered and
                    // clicked, contradicting the consent copy. A fixed
                    // window with its own scroll renders identically
                    // in both the walk and the full-screen host.
                    modifier = Modifier
                        .heightIn(min = 160.dp, max = 320.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .testTag("moderation.consent.terms"),
                )
                current.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("moderation.consent.error"),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch { controller.agree()?.let(onConsented) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("moderation.consent.agree"),
                ) { Text(stringResource(R.string.moderation_consent_agree)) }
            }

            is ModerationConsentController.UiState.Consented -> {
                // Terminal; the host navigated on [onConsented].
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.moderation_consent_done))
                }
            }
        }
    }
}
