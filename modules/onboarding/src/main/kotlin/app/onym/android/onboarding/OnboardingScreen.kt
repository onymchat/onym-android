package app.onym.android.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import kotlinx.coroutines.launch

/**
 * The first-launch onboarding surface: one [OnboardingScaffold] per
 * [OnboardingStep], driven by an [OnboardingFlow].
 *
 * Presentation contract: RootScreen shows this full-screen with
 * system-back blocked before the tab/nav flow; the only exits are the
 * Done step's primary action ([OnboardingFlow.complete] →
 * `state.completed`) and the recovery step's "Remind me later".
 *
 * Dependency posture: this module renders the *frame* of each step.
 * The steps' real content — the identity checklist, the services
 * hub, the recovery reveal — lives at the app layer and arrives
 * through [stepContent]; the built-ins keep the module previews and
 * tests standalone.
 */
@Composable
fun OnboardingScreen(
    flow: OnboardingFlow,
    modifier: Modifier = Modifier,
    /**
     * App-supplied content slot, consulted for EVERY step (Welcome
     * and Done included). Return null to fall back to the built-ins:
     * the Welcome/Done bodies, and a placeholder card for the middle
     * steps.
     */
    stepContent: (OnboardingStep) -> (@Composable () -> Unit)? = { null },
    /** App-supplied step indicator, given (zero-based index, count).
     *  null renders the built-in dot indicator. Only invoked on the
     *  steps that carry an [OnboardingFlow.Indicator]. */
    stepIndicator: (@Composable (index: Int, count: Int) -> Unit)? = null,
) {
    LaunchedEffect(flow) { flow.start() }
    val state by flow.state.collectAsState()
    val step = state.step
    val scope = rememberCoroutineScope()
    val indicator = flow.indicator(step)

    OnboardingScaffold(
        step = step,
        title = stringResource(titleRes(step)),
        subtitle = subtitleRes(step)?.let { stringResource(it) },
        primaryTitle = stringResource(primaryTitleRes(step)),
        // Outcome-gated steps render their primary disabled until the
        // step content records a SATISFYING outcome (the identity
        // bootstrap check, the recovery reveal) — a surviving Skipped
        // from an earlier "Remind me later" does not count, so
        // skip-then-Back re-disables the primary. `flow.advance()`
        // carries the same guard as the second layer. (The collected
        // `state` already recomposes this on every outcomes change;
        // the predicate reads the same StateFlow value.)
        primaryEnabled = !(flow.requiresOutcomeToAdvance(step) && !flow.outcomeSatisfiesGate(step)),
        onPrimary = {
            if (step == OnboardingStep.Done) {
                scope.launch { flow.complete() }
            } else {
                flow.advance()
            }
        },
        onSkip = if (flow.isSkippable(step)) ({ flow.skip() }) else null,
        skipTitle = stringResource(skipTitleRes(step)),
        // While the directory probe is unresolved, moderation's
        // gating is unknown (failed closed) — show progress in the
        // skip slot instead of nothing.
        showSkipProgress = step == OnboardingStep.Moderation && !state.moderationProbeResolved,
        onBack = if (step != OnboardingStep.Welcome) ({ flow.back() }) else null,
        modifier = modifier,
        // Unnumbered steps pass null so the scaffold never reserves a
        // blank indicator band.
        indicator = indicator?.let { position ->
            {
                if (stepIndicator != null) {
                    stepIndicator(position.index, position.count)
                } else {
                    DefaultStepIndicator(index = position.index, count = position.count)
                }
            }
        },
    ) {
        val injected = stepContent(step)
        if (injected != null) {
            injected()
        } else {
            when (step) {
                OnboardingStep.Welcome -> WelcomeStepContent()
                OnboardingStep.Done -> DoneStepContent(state = state)
                else -> PlaceholderStepContent(step)
            }
        }
    }
}

// ── Copy ──────────────────────────────────────────────────────────

private fun titleRes(step: OnboardingStep): Int = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_title
    OnboardingStep.Identity -> R.string.onboarding_identity_title
    OnboardingStep.Services -> R.string.onboarding_services_title
    OnboardingStep.Moderation -> R.string.onboarding_moderation_title
    OnboardingStep.RecoveryPhrase -> R.string.onboarding_recovery_title
    OnboardingStep.Done -> R.string.onboarding_done_title
}

private fun subtitleRes(step: OnboardingStep): Int? = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_subtitle
    OnboardingStep.Identity -> R.string.onboarding_identity_subtitle
    OnboardingStep.Services -> R.string.onboarding_services_subtitle
    OnboardingStep.Moderation -> R.string.onboarding_moderation_subtitle
    OnboardingStep.RecoveryPhrase -> R.string.onboarding_recovery_subtitle
    OnboardingStep.Done -> R.string.onboarding_done_subtitle
}

private fun primaryTitleRes(step: OnboardingStep): Int = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_primary_welcome
    OnboardingStep.RecoveryPhrase -> R.string.onboarding_primary_recovery
    OnboardingStep.Done -> R.string.onboarding_primary_done
    else -> R.string.onboarding_continue
}

/** The skip affordance's label — only the recovery step has one, and
 *  it reads as a deferral, not a skip. */
private fun skipTitleRes(step: OnboardingStep): Int = when (step) {
    OnboardingStep.RecoveryPhrase -> R.string.onboarding_skip_recovery
    else -> R.string.onboarding_skip
}

// ── Built-in step content ─────────────────────────────────────────

/** Default dot indicator; the app may replace it via the slot. */
@Composable
private fun DefaultStepIndicator(index: Int, count: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics {},
    ) {
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

/** Welcome step body: framing copy in a Settings card. The app layer
 *  replaces this with the OnymMark-branded bullet version. */
@Composable
private fun WelcomeStepContent() {
    SettingsCard {
        Text(
            text = stringResource(R.string.onboarding_welcome_bullet_identity),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Built-in Done step body: a plain per-step outcome summary. The app
 * layer replaces this with the live "YOUR SETUP" repository summary;
 * this built-in keeps the module previews and tests standalone.
 */
@Composable
private fun DoneStepContent(state: OnboardingFlow.State) {
    val summarySteps = listOf(OnboardingStep.Services, OnboardingStep.RecoveryPhrase)
    SettingsCard {
        summarySteps.forEach { step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(titleRes(step)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(outcomeLabelRes(step, state)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun outcomeLabelRes(step: OnboardingStep, state: OnboardingFlow.State): Int {
    // The recovery row reports how far the backup ACTUALLY got —
    // "saw the words" and "verified them" both record the same
    // consent outcome, so the outcome alone can't tell them apart.
    if (step == OnboardingStep.RecoveryPhrase) {
        return when (state.recoveryBackupState) {
            RecoveryBackupState.Verified -> R.string.onboarding_recovery_status_verified
            RecoveryBackupState.Revealed -> R.string.onboarding_recovery_status_revealed
            RecoveryBackupState.None -> R.string.onboarding_outcome_default
        }
    }
    return when (state.outcomes[step]) {
        is StepOutcome.Consented -> R.string.onboarding_outcome_chosen
        StepOutcome.Unavailable -> R.string.onboarding_outcome_unavailable
        StepOutcome.Skipped, StepOutcome.NotApplicable, null ->
            R.string.onboarding_outcome_default
    }
}

/** Placeholder body for the middle steps when the app doesn't inject
 *  real surfaces through the content slot. */
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
