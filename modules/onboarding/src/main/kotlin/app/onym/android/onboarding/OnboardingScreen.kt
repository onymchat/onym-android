package app.onym.android.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import kotlinx.coroutines.launch

/**
 * The first-launch onboarding surface: one [OnboardingScaffold] per
 * [OnboardingStep], driven by an [OnboardingFlow].
 *
 * Presentation contract (PR 3): RootScreen shows this full-screen
 * with system-back blocked before the tab/nav flow; the only exits
 * are the Done step's primary action ([OnboardingFlow.complete] →
 * `state.completed`) and the per-step Skip affordances.
 *
 * Dependency posture: this module renders the *frame* of each step.
 * The middle steps' real content — discovery confirm, catalog
 * pickers — lives at the app layer and arrives through [stepContent]
 * in PR 3; the built-ins keep the module previews and tests
 * standalone.
 */
@Composable
fun OnboardingScreen(
    flow: OnboardingFlow,
    modifier: Modifier = Modifier,
    /**
     * App-supplied content slot, consulted for EVERY step (Welcome
     * and Done included). Return null to fall back to the built-ins:
     * the Welcome/Done bodies, and a placeholder card for the middle
     * steps until PR 3 supplies the real surfaces.
     */
    stepContent: (OnboardingStep) -> (@Composable () -> Unit)? = { null },
    /** App-supplied step indicator, given (zero-based index, count).
     *  null renders the built-in dot indicator. */
    stepIndicator: (@Composable (index: Int, count: Int) -> Unit)? = null,
) {
    LaunchedEffect(flow) { flow.start() }
    val state by flow.state.collectAsState()
    val step = state.step
    val scope = rememberCoroutineScope()

    OnboardingScaffold(
        step = step,
        title = stringResource(titleRes(step)),
        subtitle = subtitleRes(step)?.let { stringResource(it) },
        primaryTitle = stringResource(
            if (step == OnboardingStep.Done) R.string.onboarding_start else R.string.onboarding_continue,
        ),
        // Mandatory steps render Continue disabled until the step
        // content records an outcome; `flow.advance()` carries the
        // same guard as the second layer.
        primaryEnabled = !(flow.isMandatory(step) && state.outcomes[step] == null),
        onPrimary = {
            if (step == OnboardingStep.Done) {
                scope.launch { flow.complete() }
            } else {
                flow.advance()
            }
        },
        onSkip = if (flow.isSkippable(step)) ({ flow.skip() }) else null,
        // While the directory probe is unresolved, moderation's
        // skippability is unknown (failed closed) — show progress
        // where Skip would be instead of nothing.
        showSkipProgress = step == OnboardingStep.Moderation && !state.moderationProbeResolved,
        onBack = if (step != OnboardingStep.Welcome) ({ flow.back() }) else null,
        modifier = modifier,
        indicator = {
            if (stepIndicator != null) {
                stepIndicator(flow.stepIndex, flow.stepCount)
            } else {
                DefaultStepIndicator(index = flow.stepIndex, count = flow.stepCount)
            }
        },
    ) {
        val injected = stepContent(step)
        if (injected != null) {
            injected()
        } else {
            when (step) {
                OnboardingStep.Welcome -> WelcomeStepContent()
                OnboardingStep.Done -> DoneStepContent(flow = flow, outcomes = state.outcomes)
                else -> PlaceholderStepContent(step)
            }
        }
    }
}

// ── Copy ──────────────────────────────────────────────────────────

private fun titleRes(step: OnboardingStep): Int = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_title
    OnboardingStep.DiscoveryConfirm -> R.string.onboarding_discovery_confirm_title
    OnboardingStep.MessageTransport -> R.string.onboarding_message_transport_title
    OnboardingStep.BlobTransport -> R.string.onboarding_blob_transport_title
    OnboardingStep.Notary -> R.string.onboarding_notary_title
    OnboardingStep.Moderation -> R.string.onboarding_moderation_title
    OnboardingStep.Done -> R.string.onboarding_done_title
}

private fun subtitleRes(step: OnboardingStep): Int? = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_subtitle
    OnboardingStep.DiscoveryConfirm -> R.string.onboarding_discovery_confirm_subtitle
    OnboardingStep.MessageTransport -> R.string.onboarding_message_transport_subtitle
    OnboardingStep.BlobTransport -> R.string.onboarding_blob_transport_subtitle
    OnboardingStep.Notary -> R.string.onboarding_notary_subtitle
    OnboardingStep.Moderation -> R.string.onboarding_moderation_subtitle
    OnboardingStep.Done -> R.string.onboarding_done_subtitle
}

// ── Built-in step content ─────────────────────────────────────────

/** Default dot indicator; PR 3 may replace it via the slot. */
@Composable
private fun DefaultStepIndicator(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { position ->
            Box(
                modifier = Modifier
                    .size(if (position == index) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (position == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/** Welcome step body: framing copy in a Settings card. */
@Composable
private fun WelcomeStepContent() {
    SettingsCard {
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Done step body: summary of what was chosen per middle step (only
 * the steps actually in this flow's sequence — the reserved
 * moderation row appears only when the flag enabled it), plus the
 * backup nudge (identity was created silently; the phrase flow lives
 * in Settings and is nudged here, never forced).
 */
@Composable
private fun DoneStepContent(
    flow: OnboardingFlow,
    outcomes: Map<OnboardingStep, StepOutcome>,
) {
    val summarySteps = flow.steps.filterNot {
        it == OnboardingStep.Welcome || it == OnboardingStep.Done
    }
    Column {
        SettingsCard {
            summarySteps.forEachIndexed { index, step ->
                SettingsRow(
                    title = stringResource(titleRes(step)),
                    trailing = {
                        Text(
                            text = stringResource(outcomeLabelRes(outcomes[step])),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    isLast = index == summarySteps.lastIndex,
                    insetHairline = 16.dp,
                )
            }
        }
        SettingsFootnote(stringResource(R.string.onboarding_done_backup_nudge))
    }
}

private fun outcomeLabelRes(outcome: StepOutcome?): Int = when (outcome) {
    is StepOutcome.Consented -> R.string.onboarding_outcome_chosen
    StepOutcome.Skipped -> R.string.onboarding_outcome_default
    StepOutcome.NotApplicable, null -> R.string.onboarding_outcome_none
}

/** Placeholder body for the middle steps until PR 3 injects the real
 *  surfaces through the content slot. */
@Composable
private fun PlaceholderStepContent(step: OnboardingStep) {
    SettingsCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(titleRes(step)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
