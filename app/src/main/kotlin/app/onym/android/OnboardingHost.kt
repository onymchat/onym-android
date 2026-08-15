package app.onym.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.onym.android.design.OnymMark
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.onboarding.OnboardingFlow
import app.onym.android.onboarding.OnboardingScreen
import app.onym.android.onboarding.OnboardingStep
import app.onym.android.onboarding.RecoveryBackupState
import app.onym.android.onboarding.ServicesChoice
import app.onym.android.onboarding.StepOutcome
import app.onym.android.recovery.RecoveryPhraseBackupScreen
import app.onym.android.recovery.RecoveryPhraseBackupViewModel
import app.onym.android.settings.ModuleConsentScreen
import app.onym.android.settings.ModuleConsentViewModel
import app.onym.android.onboarding.R as OnboardingR

/**
 * The first-launch onboarding presentation: mounts the :onboarding
 * module's [OnboardingScreen] full-screen with system back blocked,
 * injects the redesigned step contents (identity checklist, services
 * recommended/custom split, recovery-phrase backup, live Done
 * summary), and hosts the two full-screen overlays above the walk:
 * the "choose services myself" hub (a nested NavHost — see
 * [OnboardingServicesHubOverlay]) and the recovery-phrase backup
 * flow.
 *
 * Back-handling contract: the host's [BackHandler] swallows system
 * back so the walk can't be dismissed — but Compose dispatches back
 * to the MOST RECENTLY composed enabled handler, so the hub overlay's
 * nested NavHost (composed after this handler, deeper in the tree)
 * still receives back-presses first and pops its own seat screens.
 * Only when the hub is at its root (nothing to pop, its handler
 * disabled) does back fall through to this swallow — matching the
 * iOS hub's `interactiveDismissDisabled`: the hub's exits are Done
 * and "Use the recommended setup instead".
 *
 * Reuses the Settings surfaces rather than re-implementing them: the
 * hub's seat screens drive the exact ViewModels Settings uses, the
 * consent flow is the same [ModuleConsentScreen] (with the same
 * pre-bound per-seat apply closures — the blossom seat's apply runs
 * `addEndpoint(makeActive = true)` from #211), and the recovery
 * reveal is the same [RecoveryPhraseBackupScreen] (biometric gate,
 * reveal, verify quiz).
 */
/**
 * Retains the [OnboardingFlow] across configuration changes: rotation
 * or a locale/theme switch recreates the Activity, and a `remember`ed
 * flow would restart the walk at Welcome. Holding it in a ViewModel
 * keeps the exact same state-machine instance. Its scope also homes
 * any step-content repository writes that must outlive the step they
 * were tapped on.
 */
internal class OnboardingHostViewModel(val flow: OnboardingFlow) : ViewModel()

@Composable
internal fun OnboardingHost(
    dependencies: AppDependencies,
    onboarding: OnboardingUiDependencies,
) {
    // Keyed on the presentation generation: an explicit restart bumps
    // it, so the re-run gets a FRESH flow instead of the retained VM
    // whose prior walk already published `completed`.
    val generation by onboarding.generation.collectAsStateWithLifecycle()
    val hostViewModel: OnboardingHostViewModel = viewModel(
        key = "onboarding.host.$generation",
        factory = viewModelFactory {
            initializer { OnboardingHostViewModel(onboarding.makeFlow()) }
        },
    )
    val flow = hostViewModel.flow
    val state by flow.state.collectAsState()

    // Full-screen and back-blocked: the only exits are Done's primary
    // action and the recovery step's "Remind me later". Composed
    // FIRST so every overlay's own BackHandler (registered later)
    // takes precedence while mounted.
    BackHandler(enabled = true) {}

    LaunchedEffect(state.completed) {
        if (state.completed) onboarding.onCompleted()
    }

    // Overlay state, held at the host (not per-step) so the overlays
    // survive the step content recomposing beneath them.
    var hubVisible by remember { mutableStateOf(false) }
    var backupVisible by remember { mutableStateOf(false) }
    var consentEntry by remember { mutableStateOf<AttributedCatalogEntry?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(Modifier.fillMaxSize()) {
            OnboardingScreen(
                flow = flow,
                stepContent = { step ->
                    when (step) {
                        OnboardingStep.Welcome -> ({ WelcomeStepContent() })
                        OnboardingStep.Identity -> ({
                            IdentityStepContent(
                                flow = flow,
                                identityReady = onboarding.identityReady,
                            )
                        })
                        OnboardingStep.Services -> ({
                            ServicesStepContent(
                                flow = flow,
                                onOpenHub = { hubVisible = true },
                            )
                        })
                        OnboardingStep.RecoveryPhrase -> ({
                            RecoveryStepContent(
                                flow = flow,
                                onReveal = { backupVisible = true },
                            )
                        })
                        OnboardingStep.Done -> ({
                            DoneStepContent(
                                dependencies = dependencies,
                                onboarding = onboarding,
                                flow = flow,
                            )
                        })
                        // Moderation stays reserved — no Android
                        // surface yet; the module placeholder renders
                        // if the flag ever flips without content.
                        OnboardingStep.Moderation -> null
                    }
                },
            )

            if (hubVisible) {
                OnboardingServicesHubOverlay(
                    dependencies = dependencies,
                    flow = flow,
                    onOpenConsent = { entry -> consentEntry = entry },
                    onUseRecommended = {
                        // Only the choice reverts — consent pins and
                        // endpoints the sub-surfaces persisted stay
                        // (the hub's footnote says so); explicitly
                        // accepting the recommended path is still a
                        // consent.
                        flow.recordServicesChoice(ServicesChoice.Recommended)
                        flow.recordOutcome(StepOutcome.Consented(null))
                        hubVisible = false
                    },
                    onDone = {
                        flow.recordServicesChoice(ServicesChoice.Custom)
                        // The hub's seat surfaces record specific
                        // consents (with componentIds) as they happen
                        // — keep those; otherwise closing the hub
                        // still means "I set things up myself". All
                        // seats share the single services outcome, so
                        // what survives is whichever seat consented
                        // LAST — arbitrary but harmless: the Done
                        // summary reads live repository state.
                        val existing =
                            flow.state.value.outcomes[OnboardingStep.Services]
                        if ((existing as? StepOutcome.Consented)?.componentId == null) {
                            flow.recordOutcome(StepOutcome.Consented(null))
                        }
                        hubVisible = false
                    },
                )
            }

            if (backupVisible) {
                RecoveryBackupOverlay(
                    dependencies = dependencies,
                    flow = flow,
                    onDismiss = { backupVisible = false },
                )
            }

            consentEntry?.let { entry ->
                val discovery = dependencies.discovery
                if (discovery != null) {
                    // The Settings consent surface, verbatim, as a
                    // full-screen overlay. viewModel(key-per-entry)
                    // scopes the VM to the host's store — cleared
                    // with it, reused on a reopen. Closing records a
                    // Consented outcome exactly when the flow reached
                    // Done (pin + selection + apply all landed) and
                    // the walk is still on the services step.
                    val consentViewModel: ModuleConsentViewModel = viewModel(
                        key = "onboarding.consent.${entry.entry.componentId}",
                        factory = viewModelFactory {
                            initializer {
                                discovery.makeModuleConsentViewModel(
                                    entry.entry.seatType,
                                    ModuleConsentViewModel.shortComponentId(
                                        entry.entry.componentId,
                                    ),
                                )
                            }
                        },
                    )
                    ModuleConsentScreen(
                        viewModel = consentViewModel,
                        onClose = {
                            val consented = consentViewModel.state.value.step ==
                                ModuleConsentViewModel.Step.Done
                            if (consented &&
                                flow.state.value.step == OnboardingStep.Services
                            ) {
                                flow.recordOutcome(
                                    StepOutcome.Consented(entry.entry.componentId),
                                )
                            }
                            consentEntry = null
                        },
                    )
                }
            }
        }
    }
}

// ── Welcome ───────────────────────────────────────────────────────

/** Brand framing: the OnymMark over three "yours, not ours" bullets.
 *  No restore-from-phrase entry yet (deferred, parity with iOS). */
@Composable
private fun WelcomeStepContent() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            OnymMark(size = 88.dp, color = MaterialTheme.colorScheme.primary, strokeRatio = 0.14f)
        }
        SettingsCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WelcomeBullet(
                    icon = Icons.Filled.Lock,
                    text = stringResource(OnboardingR.string.onboarding_welcome_bullet_identity),
                )
                WelcomeBullet(
                    icon = Icons.Filled.SettingsInputAntenna,
                    text = stringResource(OnboardingR.string.onboarding_welcome_bullet_transport),
                )
                WelcomeBullet(
                    icon = Icons.Filled.Shield,
                    text = stringResource(OnboardingR.string.onboarding_welcome_bullet_reports),
                )
            }
        }
    }
}

@Composable
private fun WelcomeBullet(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Identity ──────────────────────────────────────────────────────

/**
 * "Making your keys" — the identity bootstrap already runs at app
 * start; this step BINDS to it instead of asserting success it can't
 * know about. The checklist shows progress while the bootstrap
 * resolves, checks once a snapshot exists, and an explicit failure
 * card (with retry) if it doesn't. The step is outcome-gated
 * ([OnboardingFlow.requiresOutcomeToAdvance]): Continue stays
 * disabled until a snapshot exists — the fail-closed dead end is
 * intentional, because a failed bootstrap must not walk the user on
 * to services and a recovery step whose reveal cannot work.
 */
@Composable
private fun IdentityStepContent(
    flow: OnboardingFlow,
    identityReady: suspend () -> Boolean,
) {
    var phase by remember { mutableStateOf(IdentityPhase.Checking) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        phase = IdentityPhase.Checking
        if (identityReady()) {
            phase = IdentityPhase.Ready
            // Unlocks the step's Continue — nothing was decided here,
            // but the gate needs proof the keys exist. recordOutcome
            // writes to the CURRENT step; guard against the check
            // resolving after a quick Back.
            if (flow.state.value.step == OnboardingStep.Identity) {
                flow.recordOutcome(StepOutcome.NotApplicable)
            }
        } else {
            phase = IdentityPhase.Failed
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsCard(modifier = Modifier.testTag("onboarding.identity.checklist")) {
            IdentityChecklistRow(
                phase = phase,
                title = stringResource(OnboardingR.string.onboarding_identity_row_key_title),
                subtitle = stringResource(OnboardingR.string.onboarding_identity_row_key_subtitle),
            )
            SettingsHairline(insetStart = 52.dp)
            IdentityChecklistRow(
                phase = phase,
                title = stringResource(OnboardingR.string.onboarding_identity_row_phrase_title),
                subtitle = stringResource(
                    OnboardingR.string.onboarding_identity_row_phrase_subtitle,
                ),
            )
        }

        if (phase == IdentityPhase.Failed) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SettingsTile.Red.copy(alpha = 0.10f))
                    .padding(14.dp)
                    .testTag("onboarding.identity.failed"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = SettingsTile.Red,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(OnboardingR.string.onboarding_identity_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(OnboardingR.string.onboarding_identity_retry),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { attempt += 1 }
                            .testTag("onboarding.identity.retry"),
                    )
                }
            }
        }

        SettingsFootnote(stringResource(OnboardingR.string.onboarding_identity_footnote))
    }
}

private enum class IdentityPhase { Checking, Ready, Failed }

@Composable
private fun IdentityChecklistRow(phase: IdentityPhase, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            IdentityPhase.Checking -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
            IdentityPhase.Ready -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = SettingsTile.Green,
                modifier = Modifier.size(20.dp),
            )
            IdentityPhase.Failed -> Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = SettingsTile.Red,
                modifier = Modifier.size(20.dp),
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Services ──────────────────────────────────────────────────────

/**
 * "Your services" — one screen instead of the old four wizard steps.
 * The recommended setup is preselected (and the flow SEEDS the
 * services outcome as an accepted recommendation at construction, so
 * the "Selected" chip and the recorded outcome tell the same story
 * from the first frame); "Choose services myself" opens the hub
 * overlay, whose per-seat screens reuse the Settings surfaces.
 *
 * The recommended card's promised lines name the FIXED recommended
 * set (the seeded defaults plus the published lists installed on
 * completion) — they are a promise, not live state; the Done step's
 * summary is the live, checkable view.
 */
@Composable
private fun ServicesStepContent(
    flow: OnboardingFlow,
    onOpenHub: () -> Unit,
) {
    val state by flow.state.collectAsState()
    // Derived from the flow, never composable-local — Back/forward
    // navigation rebuilds this content, and the chip must keep
    // telling the truth about what the user configured.
    val usingCustom = state.servicesChoice == ServicesChoice.Custom

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServicesCard(
            selected = !usingCustom,
            testTag = "onboarding.services.recommended",
            onClick = {
                // An explicit affirmative pick of the recommended
                // setup is a consent — recording over the seeded
                // outcome is an honest no-op.
                flow.recordServicesChoice(ServicesChoice.Recommended)
                flow.recordOutcome(StepOutcome.Consented(null))
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        OnboardingR.string.onboarding_services_recommended_title,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!usingCustom) SelectedChip()
            }
            Text(
                text = stringResource(
                    OnboardingR.string.onboarding_services_recommended_subtitle,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            SummaryLine(
                OnboardingR.string.onboarding_services_line_delivery,
                OnboardingR.string.onboarding_services_value_delivery,
            )
            SummaryLine(
                OnboardingR.string.onboarding_services_line_media,
                OnboardingR.string.onboarding_services_value_media,
            )
            SummaryLine(
                OnboardingR.string.onboarding_services_line_group,
                OnboardingR.string.onboarding_services_value_group,
            )
            SummaryLine(
                OnboardingR.string.onboarding_services_line_directory,
                OnboardingR.string.onboarding_services_value_directory,
            )
        }

        ServicesCard(
            selected = usingCustom,
            testTag = "onboarding.services.custom",
            onClick = onOpenHub,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(OnboardingR.string.onboarding_services_custom_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (usingCustom) SelectedChip()
            }
            Text(
                text = stringResource(OnboardingR.string.onboarding_services_custom_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(
            text = stringResource(OnboardingR.string.onboarding_services_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ServicesCard(
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 2.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(testTag),
    ) { content() }
}

@Composable
private fun SelectedChip() {
    Text(
        text = stringResource(OnboardingR.string.onboarding_services_selected_chip),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SummaryLine(labelRes: Int, valueRes: Int) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = stringResource(valueRes),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Recovery phrase ───────────────────────────────────────────────

/**
 * "Save your recovery phrase" — the backup walk, promoted from the
 * old Done-step footnote to a real step. The reveal launches the
 * SAME [RecoveryPhraseBackupScreen] Settings uses (biometric gate,
 * reveal, verify quiz), as a full-screen overlay.
 *
 * The step's primary ("I've written it down") stays disabled until
 * an outcome is recorded ([OnboardingFlow.requiresOutcomeToAdvance])
 * — and the outcome is recorded when the phrase is actually
 * REVEALED, so the button can't assert something the user never saw.
 * "Remind me later" (the step's skip) is the honest escape.
 */
@Composable
private fun RecoveryStepContent(
    flow: OnboardingFlow,
    onReveal: () -> Unit,
) {
    val state by flow.state.collectAsState()
    val backup = state.recoveryBackupState
    val backedUp = backup == RecoveryBackupState.Verified
    val sawPhrase = backup != RecoveryBackupState.None

    val statusColor = if (backedUp) SettingsTile.Green else SettingsTile.Amber
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(statusColor.copy(alpha = 0.10f))
                .padding(14.dp)
                // onym:allow-secret-read: a semantics test tag — the
                // mandated onboarding.recoveryPhrase.* vocabulary, no
                // mnemonic is read here.
                .testTag("onboarding.recoveryPhrase.status"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (backedUp) Icons.Filled.Verified else Icons.Filled.Key,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(
                        when (backup) {
                            RecoveryBackupState.Verified ->
                                OnboardingR.string.onboarding_recovery_status_verified
                            RecoveryBackupState.Revealed ->
                                OnboardingR.string.onboarding_recovery_status_revealed
                            RecoveryBackupState.None ->
                                OnboardingR.string.onboarding_recovery_status_none
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        when (backup) {
                            RecoveryBackupState.Verified ->
                                OnboardingR.string.onboarding_recovery_body_verified
                            RecoveryBackupState.Revealed ->
                                OnboardingR.string.onboarding_recovery_body_revealed
                            RecoveryBackupState.None ->
                                OnboardingR.string.onboarding_recovery_body_none
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(
            onClick = onReveal,
            modifier = Modifier
                .fillMaxWidth()
                // onym:allow-secret-read: a semantics test tag — see above.
                .testTag("onboarding.recoveryPhrase.reveal"),
        ) {
            Text(
                stringResource(
                    if (sawPhrase) {
                        OnboardingR.string.onboarding_recovery_view_again
                    } else {
                        OnboardingR.string.onboarding_recovery_reveal
                    },
                ),
            )
        }

        Text(
            text = stringResource(OnboardingR.string.onboarding_recovery_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * The recovery backup flow as a full-screen overlay above the walk —
 * the exact Settings surface. Progress is mirrored onto the
 * onboarding flow:
 * - the reveal (words on screen) sets [RecoveryBackupState.Revealed]
 *   and records the step's consent outcome — that is what makes
 *   "I've written it down" honest and tappable;
 * - completing the verify quiz sets [RecoveryBackupState.Verified]
 *   (never downgraded afterwards);
 * - the backup flow's Done screen loops back to its intro (a
 *   Settings-era behavior) — in onboarding that transition dismisses
 *   the overlay back to the step instead of stranding the user.
 */
@Composable
private fun RecoveryBackupOverlay(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OnymApplication
    val activityProvider = remember(app) { { app.requireCurrentFragmentActivity() } }
    val viewModel: RecoveryPhraseBackupViewModel = viewModel(
        key = "onboarding.recoveryBackup",
        factory = viewModelFactory {
            initializer { dependencies.makeRecoveryPhraseBackupViewModel(activityProvider) }
        },
    )

    // Back closes the overlay (never the walk) — composed after the
    // host's swallow-all handler, so it wins while mounted.
    BackHandler(enabled = true) { onDismiss() }

    LaunchedEffect(viewModel) {
        var previous: RecoveryPhraseBackupViewModel.Step? = null
        viewModel.step.collect { step ->
            // recordOutcome writes to the flow's CURRENT step — same
            // async-callback hazard the identity step guards against;
            // refuse to record from anywhere else. The backup-state
            // note is safe either way (it targets no step and never
            // downgrades).
            val onRecoveryStep = flow.state.value.step == OnboardingStep.RecoveryPhrase
            when (step) {
                is RecoveryPhraseBackupViewModel.Step.Reveal -> if (step.revealed) {
                    flow.recordRecoveryBackup(RecoveryBackupState.Revealed)
                    if (onRecoveryStep) flow.recordOutcome(StepOutcome.Consented(null))
                }
                is RecoveryPhraseBackupViewModel.Step.Done -> {
                    flow.recordRecoveryBackup(RecoveryBackupState.Verified)
                    if (onRecoveryStep) flow.recordOutcome(StepOutcome.Consented(null))
                }
                is RecoveryPhraseBackupViewModel.Step.Intro ->
                    if (previous is RecoveryPhraseBackupViewModel.Step.Done) {
                        onDismiss()
                    }
                else -> Unit
            }
            previous = step
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        RecoveryPhraseBackupScreen(
            viewModel = viewModel,
            onBackClick = onDismiss,
        )
    }
}

// ── Done ──────────────────────────────────────────────────────────

/**
 * "You're ready" — the live summary. Read from the repositories, not
 * the walk's outcomes: what the app will actually use is the
 * checkable claim. Each row carries the chosen service's name, the
 * checkable detail (endpoint URL / key fingerprint) on its own
 * monospaced line, and a per-locale plural count.
 */
@Composable
private fun DoneStepContent(
    dependencies: AppDependencies,
    onboarding: OnboardingUiDependencies,
    flow: OnboardingFlow,
) {
    val state by flow.state.collectAsState()
    val nostr by dependencies.nostrRelaysFlow.collectAsStateWithLifecycle()
    val blossom by dependencies.blossomServersFlow.collectAsStateWithLifecycle()
    val relayer by onboarding.relayerState.collectAsStateWithLifecycle()
    val discoveryFlow = dependencies.discovery?.stateFlow
    val discoveryState = discoveryFlow?.let { it.collectAsStateWithLifecycle().value }

    Column(Modifier.fillMaxWidth()) {
        // Decorative hero — hidden from accessibility.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(SettingsTile.Green),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        SettingsSectionLabel(stringResource(OnboardingR.string.onboarding_done_section_setup))
        SettingsCard(modifier = Modifier.testTag("onboarding.done.summary")) {
            val nostrFirst = nostr.endpoints.firstOrNull()
            SummaryRow(
                icon = Icons.Filled.SettingsInputAntenna,
                title = stringResource(OnboardingR.string.onboarding_hub_row_message_title),
                value = nostrFirst?.name
                    ?: stringResource(OnboardingR.string.onboarding_done_none_configured),
                detail = nostrFirst?.url,
                trailing = nostr.endpoints.size.takeIf { it > 0 }?.let {
                    pluralStringResource(
                        OnboardingR.plurals.onboarding_done_services_count,
                        it,
                        it,
                    )
                },
            )
            SettingsHairline(insetStart = 52.dp)
            val blossomFirst = blossom.endpoints.firstOrNull()
            SummaryRow(
                icon = Icons.Filled.PhotoLibrary,
                title = stringResource(OnboardingR.string.onboarding_hub_row_media_title),
                value = blossomFirst?.name
                    ?: stringResource(OnboardingR.string.onboarding_done_none_configured),
                detail = blossomFirst?.url,
                trailing = blossom.endpoints.size.takeIf { it > 0 }?.let {
                    pluralStringResource(
                        OnboardingR.plurals.onboarding_done_services_count,
                        it,
                        it,
                    )
                },
            )
            SettingsHairline(insetStart = 52.dp)
            val relayerEndpoints = relayer.configuration.endpoints
            SummaryRow(
                icon = Icons.Filled.Verified,
                title = stringResource(OnboardingR.string.onboarding_hub_row_group_title),
                // Empty means the published defaults install on
                // completion — the value already says so; a count
                // trailing would just repeat it.
                value = relayerEndpoints.firstOrNull()?.name
                    ?: stringResource(OnboardingR.string.onboarding_services_value_group),
                detail = relayerEndpoints.firstOrNull()?.url,
                trailing = relayerEndpoints.size.takeIf { it > 0 }?.let {
                    pluralStringResource(
                        OnboardingR.plurals.onboarding_done_services_count,
                        it,
                        it,
                    )
                },
            )
            val source = discoveryState?.sources?.firstOrNull()
            if (discoveryState != null && source != null) {
                SettingsHairline(insetStart = 52.dp)
                SummaryRow(
                    icon = Icons.Filled.Search,
                    title = stringResource(
                        OnboardingR.string.onboarding_hub_row_directory_title,
                    ),
                    value = source.label,
                    detail = source.operatorKeyFingerprint ?: source.url,
                    trailing = pluralStringResource(
                        OnboardingR.plurals.onboarding_done_sources_count,
                        discoveryState.sources.size,
                        discoveryState.sources.size,
                    ),
                )
            }
        }

        // Backup nudge, gated on how far the recovery step actually
        // got: hidden once revealed AND verified, softened to "finish
        // verifying" after a reveal alone, the full nudge only when
        // the step was deferred.
        if (state.recoveryBackupState != RecoveryBackupState.Verified) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SettingsTile.Amber.copy(alpha = 0.10f))
                    .padding(14.dp)
                    .testTag("onboarding.done.backup_nudge"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = SettingsTile.Amber,
                    modifier = Modifier.size(22.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val revealed =
                        state.recoveryBackupState == RecoveryBackupState.Revealed
                    Text(
                        text = stringResource(
                            if (revealed) {
                                OnboardingR.string.onboarding_done_nudge_revealed_title
                            } else {
                                OnboardingR.string.onboarding_done_nudge_full_title
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            if (revealed) {
                                OnboardingR.string.onboarding_done_nudge_revealed_body
                            } else {
                                OnboardingR.string.onboarding_done_nudge_full_body
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsFootnote(stringResource(OnboardingR.string.onboarding_done_footnote))
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String?,
    trailing: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // The checkable claim: URL / key fingerprint on its own
            // monospaced line, not folded into the value text.
            if (detail != null) {
                Text(
                    text = middleTruncate(detail),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Middle-truncate a checkable detail (URL / fingerprint) so both its
 *  scheme+host and its tail stay visible — Compose has no built-in
 *  middle ellipsis. */
internal fun middleTruncate(text: String, max: Int = 42): String {
    if (text.length <= max) return text
    val keep = (max - 1) / 2
    return text.take(keep) + "…" + text.takeLast(max - 1 - keep)
}
