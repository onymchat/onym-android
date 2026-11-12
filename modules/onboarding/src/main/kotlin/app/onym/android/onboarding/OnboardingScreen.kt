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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
 * system-back blocked before the tab/nav flow. The welcome step's door
 * ([OnboardingFlow.skipAll]) and the exit offered on every later step
 * leave with the flag persisted and no decision recorded; walking to
 * the end exits through the Done step's primary action
 * ([OnboardingFlow.complete] →
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
     * and Done included). Return null to fall back to the built-ins.
     */
    stepContent: (OnboardingStep) -> (@Composable () -> Unit)? = { null },
    /** App-supplied step indicator, given (zero-based index, count).
     *  null renders the built-in dot indicator. */
    stepIndicator: (@Composable (index: Int, count: Int) -> Unit)? = null,
    /** App-supplied footer replacing a step's button row — the welcome
     *  screen's icon row. null keeps the scaffold's own buttons (and the
     *  built-in welcome row). */
    stepFooter: (OnboardingStep) -> (@Composable () -> Unit)? = { null },
) {
    LaunchedEffect(flow) { flow.start() }
    val state by flow.state.collectAsState()
    val override by flow.primaryOverride.collectAsState()
    val step = state.step
    val scope = rememberCoroutineScope()
    val indicator = flow.indicator(step)
    val gated = flow.requiresOutcomeToAdvance(step) && !flow.outcomeSatisfiesGate(step)
    // A mandatory moderation step finishes by signing, so its primary is
    // the signature rather than an arrow (iOS parity).
    val signs = step == OnboardingStep.Moderation && flow.isMandatory(step)

    OnboardingScaffold(
        step = step,
        title = stringResource(titleRes(step, state.identityOrigin)),
        hint = subtitleRes(step, state.identityOrigin)?.let { stringResource(it) },
        symbol = symbolFor(step),
        // The welcome step's action is its door (see WelcomeStepContent).
        primaryLabel = when {
            step == OnboardingStep.Welcome -> null
            signs -> stringResource(R.string.onboarding_primary_sign)
            else -> stringResource(primaryTitleRes(step))
        },
        primaryIcon = if (signs) Icons.Filled.Gesture else primaryIconFor(step),
        // Outcome-gated steps render their primary disabled until the
        // step content records a SATISFYING outcome — unless the content
        // has taken the primary over (moderation's signature).
        primaryEnabled = !gated || override != null,
        onPrimary = {
            val action = override
            when {
                gated && action != null -> action()
                step == OnboardingStep.Done -> scope.launch { flow.complete() }
                else -> flow.advance()
            }
        },
        onSkip = if (flow.isSkippable(step)) ({ flow.skip() }) else null,
        skipLabel = stringResource(skipTitleRes(step)),
        // An alarm clock: "remind me later", not "skip for good".
        skipIcon = if (step == OnboardingStep.RecoveryPhrase) Icons.Filled.Alarm else Icons.Filled.FastForward,
        onBack = if (step != OnboardingStep.Welcome) ({ flow.back() }) else null,
        // Every step between the door and Done offers the door's exit —
        // Done's own primary already finishes.
        onExit = if (step == OnboardingStep.Welcome || step == OnboardingStep.Done) {
            null
        } else {
            ({ scope.launch { flow.skipAll() } })
        },
        centersContent = true,
        footer = stepFooter(step) ?: if (step == OnboardingStep.Welcome) {
            { WelcomeFooter(onSetup = { flow.advance() }) }
        } else {
            null
        },
        modifier = modifier,
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
                OnboardingStep.Welcome -> WelcomeStepContent(
                    onDoor = { scope.launch { flow.skipAll() } },
                )
                OnboardingStep.Done -> DoneStepContent(state = state)
                else -> PlaceholderStepContent(step)
            }
        }
    }
}

// ── Symbols ───────────────────────────────────────────────────────

/**
 * What stands on screen in place of each step's title. null where the
 * content is the symbol itself: welcome's door, identity's key (badged
 * with the bootstrap's state), recovery's life preserver.
 */
private fun symbolFor(step: OnboardingStep): ImageVector? = when (step) {
    OnboardingStep.Welcome, OnboardingStep.Identity, OnboardingStep.RecoveryPhrase -> null
    OnboardingStep.Services -> Icons.Filled.Dns
    OnboardingStep.Moderation -> Icons.Filled.Shield
    OnboardingStep.Done -> Icons.Filled.Verified
}

private fun primaryIconFor(step: OnboardingStep): ImageVector = when (step) {
    OnboardingStep.RecoveryPhrase -> Icons.Filled.Check
    OnboardingStep.Done -> Icons.Filled.MeetingRoom
    else -> Icons.AutoMirrored.Filled.ArrowForward
}

// ── Copy ──────────────────────────────────────────────────────────

private fun titleRes(step: OnboardingStep, origin: IdentityOrigin): Int = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_title
    // "Making your keys" would be a lie over an identity the user
    // just brought here from a recovery phrase — parity with iOS's
    // identityOrigin copy flip.
    OnboardingStep.Identity ->
        if (origin == IdentityOrigin.Restored) {
            R.string.onboarding_identity_title_restored
        } else {
            R.string.onboarding_identity_title
        }
    OnboardingStep.Services -> R.string.onboarding_services_title
    OnboardingStep.Moderation -> R.string.onboarding_moderation_title
    OnboardingStep.RecoveryPhrase -> R.string.onboarding_recovery_title
    OnboardingStep.Done -> R.string.onboarding_done_title
}

private fun subtitleRes(step: OnboardingStep, origin: IdentityOrigin): Int? = when (step) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_subtitle
    OnboardingStep.Identity -> R.string.onboarding_identity_subtitle
    OnboardingStep.Services -> R.string.onboarding_services_subtitle
    OnboardingStep.Moderation -> R.string.onboarding_moderation_subtitle
    OnboardingStep.RecoveryPhrase ->
        if (origin == IdentityOrigin.Restored) {
            R.string.onboarding_recovery_subtitle_restored
        } else {
            R.string.onboarding_recovery_subtitle
        }
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

/** Welcome step body for previews and standalone module tests: the
 *  door that leaves the walk. The app layer supplies the real one —
 *  same door, same tags. */
@Composable
private fun WelcomeStepContent(onDoor: () -> Unit) {
    FilledIconButton(
        onClick = onDoor,
        modifier = Modifier
            .size(112.dp)
            .testTag("onboarding.welcome.primary"),
    ) {
        Icon(
            imageVector = Icons.Filled.MeetingRoom,
            contentDescription = stringResource(R.string.onboarding_welcome_door),
            modifier = Modifier.size(48.dp),
        )
    }
}

/** The welcome screen's bottom row: step-by-step setup, as an icon. */
@Composable
private fun WelcomeFooter(onSetup: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledTonalIconButton(
            onClick = onSetup,
            modifier = Modifier
                .size(56.dp)
                .testTag("onboarding.welcome.setup"),
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = stringResource(R.string.onboarding_welcome_setup_entry),
            )
        }
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
                    text = stringResource(titleRes(step, state.identityOrigin)),
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
                // The placeholder only ever labels middle steps in
                // module previews/tests — origin-neutral by design.
                text = stringResource(titleRes(step, IdentityOrigin.Minted)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
