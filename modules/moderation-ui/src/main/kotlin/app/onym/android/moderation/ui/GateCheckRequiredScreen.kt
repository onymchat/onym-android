package app.onym.android.moderation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.moderation.CheckRequiredReason

/**
 * The blocking "Verification required" gate: no trustworthy gate
 * answer (and grace exhausted). Retry re-runs the check for the
 * reasons where retrying can help; `REIDENTIFICATION_REQUIRED` names
 * the authority path instead (device recovery is a deferred vertical —
 * the copy routes to a human, not to a dead button).
 */
@Composable
fun GateCheckRequiredScreen(
    reason: CheckRequiredReason,
    onRetry: () -> Unit,
    /** The authority's human route, rendered for
     * `REIDENTIFICATION_REQUIRED` — the one reason with no retry and
     * no ban notice to point at. */
    authorityContact: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        // Scrollable: this gate is full-screen and back-blocked, so on
        // a small viewport / large font scale the Retry button being
        // measured off the bottom would be the silent brick §5.2
        // forbids, arrived at from the layout side.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("moderation.gateRequired"),
        ) {
            Text(
                text = stringResource(R.string.moderation_gate_required_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(reasonBody(reason)),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (reason == CheckRequiredReason.REIDENTIFICATION_REQUIRED) {
                // Unconditional: this is the one blocked state with no
                // retry and no ban notice, so SOMETHING contactable
                // must render even when the mandate store yields no
                // line — a back-blocked screen with nothing tappable
                // and nothing named is the silent brick §5.2 forbids.
                Spacer(Modifier.height(12.dp))
                Text(
                    text = authorityContact
                        ?.let { stringResource(R.string.moderation_banned_contact, it) }
                        ?: stringResource(R.string.moderation_gate_required_contact_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("moderation.gateRequired.contact"),
                )
            }
            if (canRetry(reason)) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("moderation.gateRequired.retry"),
                ) { Text(stringResource(R.string.moderation_gate_required_retry)) }
            }
        }
    }
}

private fun reasonBody(reason: CheckRequiredReason): Int = when (reason) {
    CheckRequiredReason.TOKEN_INVALID -> R.string.moderation_gate_required_token_invalid
    CheckRequiredReason.ATTESTATION_UNAVAILABLE ->
        R.string.moderation_gate_required_attestation_unavailable
    CheckRequiredReason.REIDENTIFICATION_REQUIRED ->
        R.string.moderation_gate_required_reidentification
    CheckRequiredReason.OFFLINE_GRACE_EXPIRED -> R.string.moderation_gate_required_grace_expired
    CheckRequiredReason.NEVER_CHECKED -> R.string.moderation_gate_required_never_checked
    CheckRequiredReason.CLOCK_ROLLBACK -> R.string.moderation_gate_required_clock_rollback
    CheckRequiredReason.BACKEND_REFUSED -> R.string.moderation_gate_required_backend_refused
    CheckRequiredReason.ENROLLMENT_LOST -> R.string.moderation_gate_required_enrollment_lost
}

private fun canRetry(reason: CheckRequiredReason): Boolean = when (reason) {
    CheckRequiredReason.OFFLINE_GRACE_EXPIRED,
    CheckRequiredReason.NEVER_CHECKED,
    CheckRequiredReason.TOKEN_INVALID,
    CheckRequiredReason.CLOCK_ROLLBACK,
    CheckRequiredReason.BACKEND_REFUSED,
    // Retryable: the gate flow's retry re-probes the authority
    // directory, and re-consent becoming reachable is exactly what a
    // retry can change here. Without it this reason is a buttonless,
    // back-blocked brick whenever the launch-time directory probe
    // happened to fail.
    CheckRequiredReason.ENROLLMENT_LOST,
    CheckRequiredReason.ATTESTATION_UNAVAILABLE,
    -> true
    CheckRequiredReason.REIDENTIFICATION_REQUIRED,
    -> false
}
