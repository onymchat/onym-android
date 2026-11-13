package app.onym.android

import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Support
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import app.onym.android.design.SymbolHint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
import app.onym.android.foundation.Bip39
import app.onym.android.onboarding.IdentityOrigin
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
import kotlinx.coroutines.launch
import app.onym.android.onboarding.R as OnboardingR
import app.onym.android.strings.R as TermsR

/**
 * The first-launch onboarding presentation: mounts the :onboarding
 * module's [OnboardingScreen] full-screen with system back blocked,
 * injects the redesigned step contents (identity checklist, services
 * recommended/custom split, recovery-phrase backup, live Done
 * summary), and hosts the full-screen overlays above the walk:
 * the "choose services myself" hub (a nested NavHost — see
 * [OnboardingServicesHubOverlay]), the recovery-phrase backup
 * flow, and the welcome step's restore-from-phrase entry
 * ([RestoreIdentityOverlay]).
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
internal class OnboardingHostViewModel(val flow: OnboardingFlow) : ViewModel() {

    /**
     * Restore-overlay UI state, VM-held rather than composition-local
     * because the restore itself runs on [viewModelScope]: a rotation
     * mid-restore recreates the composition, and composition-local
     * flags would reset `restoring` to false (re-enabling Cancel and
     * back mid-swap) and drop an error that lands after the rotation
     * onto a dead state object. Held here, the flags outlive the
     * composition exactly like the work does.
     *
     * [restorePhrase] is secret material: it is scrubbed by
     * [clearRestoreInput] on every overlay exit (cancel, back,
     * success) and deliberately never touches saveable state — a
     * mnemonic must not land in the saved-instance Bundle on disk.
     */
    val restorePhrase = kotlinx.coroutines.flow.MutableStateFlow("")
    val restoreError = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val restoreInFlight = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun clearRestoreInput() {
        restorePhrase.value = ""
        restoreError.value = null
    }

    /** The field's edit path: a new phrase retracts the verdict on
     *  the old one. */
    fun editRestorePhrase(text: String) {
        restorePhrase.value = text
        restoreError.value = null
    }

    /**
     * Whether the welcome step may offer restore — null while the
     * probe is in flight. VM-held, not composition-held: a rotation
     * would otherwise restart the probe at "unknown", and since the
     * overlay's mount gates on this, an open (possibly mid-restore)
     * overlay would blink away and back. The VM outlives the
     * configuration change, so the answer does too.
     *
     * NOT saveable, deliberately: a process death restarts the walk
     * with seat state from the lost walk still persisted, and the
     * probe is what notices that. Re-probing after process death is
     * the fail-closed answer; restoring a stale `true` is not.
     */
    val restoreAllowed = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    private var restoreProbeStarted = false

    /** Resolve [restoreAllowed] once per retained VM. */
    fun probeRestoreAllowed(probe: suspend () -> Boolean) {
        if (restoreProbeStarted) return
        restoreProbeStarted = true
        viewModelScope.launch {
            restoreAllowed.value = try {
                probe()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Validate and run the restore. The phrase is normalized
     * ([Bip39.normalizeMnemonic]) so a paste carrying newlines, NBSP
     * or other Unicode spaces parses the same as a typed one, then
     * pre-validated with [Bip39.isValidMnemonic] — the repository
     * applies the identical check, but its typed InvalidMnemonic
     * error is internal to :identity, so the distinction must be
     * drawn before the call. On success the flow records
     * [IdentityOrigin.Restored], advances off Welcome (which is what
     * dismisses the overlay — its visibility derives from the step),
     * and the input is scrubbed.
     */
    fun submitRestore(restoreIdentity: suspend (String) -> Unit) {
        if (restoreInFlight.value) return
        val normalized = Bip39.normalizeMnemonic(restorePhrase.value)
        val words = Bip39.mnemonicWordCount(normalized)
        if (words != 12 && words != 24) return
        restoreError.value = null
        restoreInFlight.value = true
        viewModelScope.launch {
            try {
                if (!Bip39.isValidMnemonic(normalized)) {
                    restoreError.value =
                        OnboardingR.string.onboarding_restore_error_invalid
                    return@launch
                }
                restoreIdentity(normalized)
                flow.recordIdentityOrigin(IdentityOrigin.Restored)
                // advance() moves off the flow's CURRENT step — same
                // guard every async call site carries.
                if (flow.state.value.step == OnboardingStep.Welcome) {
                    flow.advance()
                }
                clearRestoreInput()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                restoreError.value =
                    OnboardingR.string.onboarding_restore_error_failed
            } finally {
                restoreInFlight.value = false
            }
        }
    }
}

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
    // survive the step content recomposing beneath them. Saveable so
    // a rotation with the hub or the reveal open re-presents the
    // overlay instead of dropping the user back to the step (the
    // backup overlay re-presents at its INTRO — its ViewModel is
    // scrubbed on dispose, see RecoveryBackupOverlay — so the
    // re-entry crosses the biometric gate again, which is desired).
    var hubVisible by rememberSaveable { mutableStateOf(false) }
    var backupVisible by rememberSaveable { mutableStateOf(false) }
    var restoreVisible by rememberSaveable { mutableStateOf(false) }
    var consentEntry by remember { mutableStateOf<AttributedCatalogEntry?>(null) }

    // Whether the welcome step may offer "I have a recovery phrase":
    // wiring present AND the probe allows it (a restart walk runs
    // over a real identity that restore would wipe). Resolved once
    // per retained VM — see [OnboardingHostViewModel.restoreAllowed]
    // for why the answer lives there rather than in the composition.
    val restoreAllowedState by hostViewModel.restoreAllowed
        .collectAsStateWithLifecycle()
    LaunchedEffect(hostViewModel) {
        hostViewModel.probeRestoreAllowed {
            onboarding.restoreIdentity != null &&
                onboarding.restoreIdentityAllowed()
        }
    }
    // Unresolved reads as "not allowed" for the ENTRY (fail-closed:
    // never offer a door we haven't checked) — the mount gate below
    // is what must not flap, and it can't: the resolved answer is
    // VM-held across rotation.
    val restoreAllowed = restoreAllowedState == true

    // The saved flags can outlive the flow: OnboardingFlow dies with
    // the process, so after process death the restored `true` would
    // mount an overlay over a walk restarted at Welcome. An overlay
    // is only ever meaningful on its own step — derive the effective
    // visibility from the step, and clear the stale flag so it can't
    // linger.
    val hubShown = hubVisible && state.step == OnboardingStep.Services
    // The backup runs from the recovery step, and again from the Done
    // step's recovery tile.
    val backupShown = backupVisible &&
        (state.step == OnboardingStep.RecoveryPhrase || state.step == OnboardingStep.Done)
    // The restore overlay carries the full gate in its mount
    // condition, not just the entry that opens it: the saved flag
    // survives process death while the probe re-answers, and this
    // guard is what stands between a stale flag and a restore() that
    // cascade-wipes chats. `neverLeftWelcome` is the other half —
    // Back from a configured services hub returns to Welcome, and
    // swapping the identity out from under seats already configured
    // (and registrations already signed) against it is exactly what
    // the gating rationale assumes never happens.
    val restoreShown = restoreVisible && restoreAllowed &&
        state.neverLeftWelcome && state.step == OnboardingStep.Welcome

    // Hoisted ABOVE the hubShown conditional: were the controller
    // created inside the overlay, closing the hub would drop the
    // NavHost without popping, and the NavBackStackEntry-scoped seat
    // ViewModels (each holding a SharingStarted.Eagerly collector)
    // would sit uncleared in the Activity's NavControllerViewModel —
    // one leaked set per open/close cycle. With the controller
    // hoisted, [closeHub] pops to the hub root on EVERY close, so
    // popped entries get onCleared() and a re-opened hub builds
    // fresh seat ViewModels (they are entry-scoped: new entry, new
    // ViewModel).
    val hubNavController = androidx.navigation.compose.rememberNavController()
    val closeHub: () -> Unit = {
        // currentBackStackEntry is null until the NavHost first sets
        // a graph — popping by route would throw then.
        if (hubNavController.currentBackStackEntry != null) {
            hubNavController.popBackStack(ROUTE_HUB, inclusive = false)
        }
        hubVisible = false
    }
    LaunchedEffect(state.step) {
        if (state.step != OnboardingStep.Services) closeHub()
        if (state.step != OnboardingStep.RecoveryPhrase && state.step != OnboardingStep.Done) {
            backupVisible = false
        }
        if (state.step != OnboardingStep.Welcome) restoreVisible = false
    }

    // PIN-ON-ACCEPT: accepting the recommendation is the explicit
    // confirm of the seeded default directory — run the programmatic
    // TOFU (same fetch→verify→pin path as the hub's Verify &
    // Confirm; see RecommendedDirectoryPinner for the trust
    // rationale). TWO triggers, same pinner, same host-retained
    // scope, same idempotence:
    //  1. here — previous step was Services AND the new step is
    //     LATER in the walk (Back to Identity never triggers) AND
    //     the choice is Recommended at that moment;
    //  2. hub Done (see onDone below) — a custom setup that LEFT the
    //     Directory seat alone still keeps the recommended default,
    //     per the hub's own promise.
    // Idempotent (no-op when already pinned or removed) and
    // non-blocking (failure leaves the source unpinned; the Done
    // summary says "Not confirmed"). Runs in the host-retained scope
    // so further navigation can't cancel the pin mid-flight.
    var stepBeforeChange by remember { mutableStateOf<OnboardingStep?>(null) }
    LaunchedEffect(state.step) {
        val previous = stepBeforeChange
        stepBeforeChange = state.step
        val services = flow.steps.indexOf(OnboardingStep.Services)
        if (previous == OnboardingStep.Services &&
            flow.steps.indexOf(state.step) > services &&
            state.servicesChoice == ServicesChoice.Recommended
        ) {
            hostViewModel.viewModelScope.launch {
                onboarding.pinRecommendedDirectory()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(Modifier.fillMaxSize()) {
            OnboardingScreen(
                flow = flow,
                stepContent = { step ->
                    when (step) {
                        OnboardingStep.Welcome -> ({
                            WelcomeDoor(
                                enabled = flow.termsSatisfied(state),
                                onDoor = {
                                    hostViewModel.viewModelScope.launch { flow.skipAll() }
                                },
                            )
                        })
                        OnboardingStep.Identity -> ({
                            IdentityStepContent(
                                flow = flow,
                                identityReady = onboarding.identityReady,
                                // The host already collects the flow's
                                // state — pass the flag down instead of
                                // mounting a second collector.
                                restored = state.identityOrigin ==
                                    IdentityOrigin.Restored,
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
                                onBackup = { backupVisible = true },
                            )
                        })
                        OnboardingStep.Moderation -> ({
                            ModerationStepContent(
                                dependencies = dependencies,
                                flow = flow,
                            )
                        })
                    }
                },
                stepFooter = { step ->
                    if (step == OnboardingStep.Welcome) {
                        {
                            WelcomeFooter(
                                // Same two-part gate as the overlay's
                                // mount: allowed AND the walk has never
                                // moved past Welcome.
                                restoreAvailable = restoreAllowed && state.neverLeftWelcome,
                                termsAccepted = state.termsAccepted,
                                enabled = flow.termsSatisfied(state),
                                onTermsAccepted = { accepted ->
                                    hostViewModel.viewModelScope.launch { flow.setTermsAccepted(accepted) }
                                },
                                onSetup = { flow.advance() },
                                onRestore = { restoreVisible = true },
                            )
                        }
                    } else {
                        null
                    }
                },
            )

            if (hubShown) {
                OnboardingServicesHubOverlay(
                    dependencies = dependencies,
                    flow = flow,
                    navController = hubNavController,
                    writeScope = hostViewModel.viewModelScope,
                    onOpenConsent = { entry -> consentEntry = entry },
                    onUseRecommended = {
                        // Only the choice reverts — consent pins and
                        // endpoints the sub-surfaces persisted stay
                        // (the hub's footnote says so); explicitly
                        // accepting the recommended path is still a
                        // consent. recordOutcome writes to the
                        // CURRENT step — same guard every other call
                        // site carries.
                        flow.recordServicesChoice(ServicesChoice.Recommended)
                        if (flow.state.value.step == OnboardingStep.Services) {
                            flow.recordOutcome(StepOutcome.Consented(null))
                        }
                        closeHub()
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
                        if ((existing as? StepOutcome.Consented)?.componentId == null &&
                            flow.state.value.step == OnboardingStep.Services
                        ) {
                            flow.recordOutcome(StepOutcome.Consented(null))
                        }
                        // Hub Done sets Custom, so the advance-time
                        // pin trigger never fires — but a seat LEFT
                        // ALONE keeps the recommended default (the
                        // hub's own promise), so a Directory seat the
                        // user never touched still gets its seeded
                        // source pinned here. The pinner's tri-state
                        // keeps this honest: an interactive pin in
                        // the seat reads AlreadyPinned, a removed
                        // seed reads SourceAbsent (respected), and
                        // replacing the seed means removing it —
                        // covered by the same signal.
                        hostViewModel.viewModelScope.launch {
                            onboarding.pinRecommendedDirectory()
                        }
                        closeHub()
                    },
                )
            }

            if (backupShown) {
                RecoveryBackupOverlay(
                    dependencies = dependencies,
                    flow = flow,
                    onDismiss = { backupVisible = false },
                )
            }

            if (restoreShown) {
                onboarding.restoreIdentity?.let { restore ->
                    RestoreIdentityOverlay(
                        hostViewModel = hostViewModel,
                        restoreIdentity = restore,
                        onDismiss = { restoreVisible = false },
                    )
                }
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
                    val closeConsent = {
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
                    }
                    // ModuleConsentScreen registers no BackHandler of
                    // its own — without this, back would pop the hub
                    // seat screen UNDERNEATH the consent overlay.
                    // Registered here (deeper than the host's swallow
                    // and the hub's NavHost) so it wins while the
                    // overlay is up, taking the same path as Close.
                    BackHandler(enabled = true) { closeConsent() }
                    ModuleConsentScreen(
                        viewModel = consentViewModel,
                        onClose = closeConsent,
                    )
                }
            }
        }
    }
}

// ── Welcome ───────────────────────────────────────────────────────

/**
 * The welcome screen, textless (iOS parity): one round door, dead centre,
 * that leaves the walk for the chats. Nothing here is a decision; the
 * walk's own consents are offered only if it is walked.
 */
@Composable
private fun WelcomeDoor(enabled: Boolean, onDoor: () -> Unit) {
    FilledIconButton(
        onClick = onDoor,
        enabled = enabled,
        modifier = Modifier
            .size(112.dp)
            .testTag("onboarding.welcome.primary"),
    ) {
        Icon(
            imageVector = Icons.Filled.MeetingRoom,
            contentDescription = stringResource(OnboardingR.string.onboarding_welcome_door),
            modifier = Modifier.size(48.dp),
        )
    }
}

/**
 * The welcome screen's bottom row: the two ways a person who wants to
 * slow down can take instead — step-by-step setup (sliders) and restore
 * from a recovery phrase (key). [restoreAvailable] gates the key: hidden
 * when the wiring is absent, when the walk runs over an identity that
 * [app.onym.android.identity.IdentityRepository.restore] would wipe, and
 * once the walk has left Welcome ([OnboardingFlow.State.neverLeftWelcome]).
 */
@Composable
private fun WelcomeFooter(
    restoreAvailable: Boolean,
    termsAccepted: Boolean,
    enabled: Boolean,
    onTermsAccepted: (Boolean) -> Unit,
    onSetup: () -> Unit,
    onRestore: () -> Unit,
) {
    var readingTerms by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    TermsAgreementRow(
        accepted = termsAccepted,
        onAcceptedChange = onTermsAccepted,
        onRead = { readingTerms = true },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
    ) {
        FilledTonalIconButton(
            onClick = onSetup,
            enabled = enabled,
            modifier = Modifier
                .size(56.dp)
                .testTag("onboarding.welcome.setup"),
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = stringResource(OnboardingR.string.onboarding_welcome_setup_entry),
            )
        }
        if (restoreAvailable) {
            FilledTonalIconButton(
                onClick = onRestore,
                enabled = enabled,
                modifier = Modifier
                    .size(56.dp)
                    .testTag("onboarding.welcome.restore"),
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = stringResource(OnboardingR.string.onboarding_welcome_restore_entry),
                )
            }
        }
    }
    }
    if (readingTerms) TermsOfUseDialog(onDismiss = { readingTerms = false })
}

/**
 * "☐ I agree to the Terms of Use" — every way off the welcome screen
 * waits on it (App Review guideline 1.2). The only words on the
 * screen: an agreement has to say what is being agreed to.
 */
@Composable
private fun TermsAgreementRow(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onRead: () -> Unit,
) {
    val checkboxLabel = stringResource(app.onym.android.strings.R.string.terms_checkbox_cd)
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(
            checked = accepted,
            onCheckedChange = onAcceptedChange,
            modifier = Modifier
                .testTag("onboarding.welcome.terms.checkbox")
                .semantics { contentDescription = checkboxLabel },
        )
        Text(
            text = stringResource(app.onym.android.strings.R.string.terms_agree_prefix),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.TextButton(
            onClick = onRead,
            modifier = Modifier.testTag("onboarding.welcome.terms.read"),
        ) {
            Text(stringResource(app.onym.android.strings.R.string.terms_link))
        }
    }
}

/** The full Terms of Use, readable before agreeing. */
@Composable
private fun TermsOfUseDialog(onDismiss: () -> Unit) {
    val sections = listOf(
        TermsR.string.terms_1_title to TermsR.string.terms_1_body,
        TermsR.string.terms_2_title to TermsR.string.terms_2_body,
        TermsR.string.terms_3_title to TermsR.string.terms_3_body,
        TermsR.string.terms_4_title to TermsR.string.terms_4_body,
        TermsR.string.terms_5_title to TermsR.string.terms_5_body,
        TermsR.string.terms_6_title to TermsR.string.terms_6_body,
    )
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag("terms.document")) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(TermsR.string.terms_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("terms.done"),
                    ) { Text(stringResource(TermsR.string.terms_done)) }
                }
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    sections.forEach { (title, body) ->
                        androidx.compose.foundation.layout.Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The welcome step's restore flow as a full-screen overlay above the
 * walk — the Android shape of iOS's `OnboardingRestoreSheet`: one
 * multiline phrase field, a 12/24 word-count gate on the submit, and
 * inline errors. The phrase is normalized ([Bip39.normalizeMnemonic])
 * before validation so a phrase pasted with newlines, NBSP or other
 * Unicode spaces — password managers, web copy, photographed word
 * grids — parses the same as a typed one.
 *
 * The submit path (normalization, validation, the restore itself,
 * origin recording, advance) lives on [OnboardingHostViewModel] —
 * see [OnboardingHostViewModel.submitRestore] — together with the
 * phrase/error/in-flight state, so a rotation mid-restore keeps the
 * flags in step with the work. Success dismisses by ADVANCING: the
 * overlay's visibility derives from the Welcome step, so no captured
 * composition callback is needed on the ViewModel path.
 */
@Composable
private fun RestoreIdentityOverlay(
    hostViewModel: OnboardingHostViewModel,
    restoreIdentity: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val phrase by hostViewModel.restorePhrase.collectAsStateWithLifecycle()
    val errorRes by hostViewModel.restoreError.collectAsStateWithLifecycle()
    val restoring by hostViewModel.restoreInFlight.collectAsStateWithLifecycle()

    val wordCount = Bip39.mnemonicWordCount(phrase)
    val canRestore = (wordCount == 12 || wordCount == 24) && !restoring

    // Every explicit exit scrubs the typed phrase from the VM — it is
    // secret material and must not linger for the rest of the walk.
    val dismiss = {
        hostViewModel.clearRestoreInput()
        onDismiss()
    }

    // Back closes the overlay (never the walk), except mid-restore —
    // the identity swap must not look cancelable once it's running.
    BackHandler(enabled = true) { if (!restoring) dismiss() }

    val submit = { hostViewModel.submitRestore(restoreIdentity) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Edge-to-edge is on app-wide and this overlay sits
                // above the walk with no inset-applying Scaffold of
                // its own (the way OnboardingScaffold and the backup
                // overlay have): without safeDrawing the title row
                // draws under the status bar and cutout, and without
                // imePadding the raised keyboard covers the submit
                // button with no way to scroll to it — the window
                // does not resize under edge-to-edge.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(OnboardingR.string.onboarding_restore_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = dismiss,
                    enabled = !restoring,
                    modifier = Modifier.testTag("onboarding.welcome.restore.cancel"),
                ) {
                    Text(stringResource(OnboardingR.string.onboarding_restore_cancel))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(OnboardingR.string.onboarding_restore_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = phrase,
                // Editing retracts the verdict: without this, deleting
                // a word to retype it leaves the red field and "that
                // doesn't look like a valid phrase" pinned to a phrase
                // that no longer exists (submitRestore only clears the
                // error AFTER its own 12/24 gate passes, which a
                // half-typed phrase never reaches).
                onValueChange = { hostViewModel.editRestorePhrase(it) },
                enabled = !restoring,
                placeholder = {
                    Text(stringResource(OnboardingR.string.onboarding_restore_hint))
                },
                minLines = 3,
                isError = errorRes != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding.welcome.restore.phrase_field"),
            )

            errorRes?.let { res ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("onboarding.welcome.restore.error"),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(OnboardingR.string.onboarding_restore_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(
                onClick = submit,
                enabled = canRestore,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding.welcome.restore.submit"),
            ) {
                Text(
                    stringResource(
                        if (restoring) {
                            OnboardingR.string.onboarding_restore_submitting
                        } else {
                            OnboardingR.string.onboarding_restore_submit
                        },
                    ),
                )
            }
        }
    }
}

// ── Identity ──────────────────────────────────────────────────────

/**
 * "Making your keys" — one key, centred by the scaffold, its state badged
 * on its corner (iOS parity): a spinner while the bootstrap resolves, a
 * green check once a snapshot exists, a red cross and a retry if it
 * doesn't. The bootstrap already runs at app start; this step BINDS to it
 * instead of asserting success it can't know about. Outcome-gated
 * ([OnboardingFlow.requiresOutcomeToAdvance]): the primary stays disabled
 * until a snapshot exists, because a failed bootstrap must not walk the
 * user on to services and a recovery step whose reveal cannot work.
 */
@Composable
private fun IdentityStepContent(
    flow: OnboardingFlow,
    identityReady: suspend () -> Boolean,
    /** Label flip for a restored identity (iOS identityOrigin parity). */
    restored: Boolean,
) {
    var phase by remember { mutableStateOf(IdentityPhase.Checking) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        phase = IdentityPhase.Checking
        if (identityReady()) {
            phase = IdentityPhase.Ready
            // Unlocks the step's primary — nothing was decided here, but
            // the gate needs proof the keys exist. recordOutcome writes to
            // the CURRENT step; guard against a resolve after a quick Back.
            if (flow.state.value.step == OnboardingStep.Identity) {
                flow.recordOutcome(StepOutcome.NotApplicable)
            }
        } else {
            phase = IdentityPhase.Failed
        }
    }

    val readyLabel = stringResource(
        if (restored) {
            OnboardingR.string.onboarding_identity_row_key_title_restored
        } else {
            OnboardingR.string.onboarding_identity_row_key_title
        },
    )
    val makingLabel = stringResource(
        if (restored) OnboardingR.string.onboarding_identity_title_restored else OnboardingR.string.onboarding_identity_title,
    )
    val failedLabel = stringResource(OnboardingR.string.onboarding_identity_failed)

    Box(
        modifier = Modifier
            .size(140.dp)
            .testTag("onboarding.identity.checklist"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.VpnKey,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(88.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                IdentityPhase.Checking -> CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = makingLabel },
                )
                IdentityPhase.Ready -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = readyLabel,
                    tint = SettingsTile.Green,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("onboarding.identity.ready"),
                )
                IdentityPhase.Failed -> Icon(
                    Icons.Filled.Cancel,
                    contentDescription = failedLabel,
                    tint = SettingsTile.Red,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("onboarding.identity.failed"),
                )
            }
        }
    }

    if (phase == IdentityPhase.Failed) {
        Spacer(Modifier.height(16.dp))
        FilledTonalIconButton(
            onClick = { attempt += 1 },
            modifier = Modifier
                .size(48.dp)
                .testTag("onboarding.identity.retry"),
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(OnboardingR.string.onboarding_identity_retry),
                tint = SettingsTile.Red,
            )
        }
    }
}

private enum class IdentityPhase { Checking, Ready, Failed }

// ── Moderation ────────────────────────────────────────────────────

/**
 * The moderation consent step, in symbols (iOS parity): the shared
 * consent surface with its authorities behind a configure icon and its
 * terms as class symbols plus the full text one tap away. The step's
 * primary IS the signature: while terms are on screen the scaffold's
 * primary calls [ModerationConsentController.agree], and once signed the
 * walk moves on by itself after the seal has shown.
 *
 * Outcome-gated: Consented on success; Unavailable when the directory
 * offers nothing (opting out of moderation is not a thing, but an empty
 * directory cannot demand consent).
 */
@Composable
private fun ModerationStepContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
) {
    // The step only enters the walk when the seat is wired; fail fast
    // and name the missing piece rather than render a bricked step.
    val moderation = checkNotNull(dependencies.moderation) {
        "OnboardingStep.Moderation is in the walk but AppDependencies.moderation is null — " +
            "OnymApplication must construct the moderation dependencies whenever it enables " +
            "the step (moderationEnabled = moderationUi != null)"
    }
    val controller = remember { moderation.makeConsentController(true) }
    val consent by controller.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    // Auto-advance only after a signature made HERE: a resumed mandate
    // (Back into this step, a restart walk) re-emits Consented, and moving
    // the user on from that would make the step impossible to revisit.
    var signedHere by remember { mutableStateOf(false) }

    LaunchedEffect(consent) {
        val reviewing = consent is app.onym.android.moderation.ui.ModerationConsentController.UiState.Review
        flow.setPrimaryOverride(
            if (reviewing && flow.state.value.step == OnboardingStep.Moderation) {
                {
                    signedHere = true
                    scope.launch { controller.agree() }
                }
            } else {
                null
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { flow.setPrimaryOverride(null) }
    }

    app.onym.android.moderation.ui.ModerationConsentContent(
        controller = controller,
        // The step scaffold already scrolls.
        standalone = false,
        // Debug/emulator builds can never pass the backend's classifier;
        // release binaries pass false and keep the strict surface.
        debugSkipAllowed = app.onym.android.BuildConfig.DEBUG,
        hostOwnsSigning = true,
        onConsented = { record ->
            if (flow.state.value.step == OnboardingStep.Moderation) {
                flow.recordOutcome(StepOutcome.Consented(record.mandate.authority))
            }
            // The iOS `consentCompleted` invariant: a fresh gate check.
            moderation.gate.consentCompleted()
            if (signedHere) {
                scope.launch {
                    delay(800)
                    if (flow.state.value.step == OnboardingStep.Moderation) flow.advance()
                }
            }
        },
        onUnavailableContinue = {
            if (flow.state.value.step == OnboardingStep.Moderation) {
                flow.recordOutcome(StepOutcome.Unavailable)
            }
            // Also defer the ROOT gate for this process, or finishing the
            // walk lands on the NeedsConsent surface just continued past.
            moderation.gate.deferConsent()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Services ──────────────────────────────────────────────────────

/**
 * "Your services", in symbols (iOS parity): the recommended set as a card
 * of its four seats' icons, "choose myself" as a sliders card, a check
 * badge on the selected one. The recommended setup is preselected (the
 * flow SEEDS the services outcome as an accepted recommendation); the
 * sliders card opens the hub overlay, whose seat screens reuse Settings.
 */
@Composable
private fun ServicesStepContent(
    flow: OnboardingFlow,
    onOpenHub: () -> Unit,
) {
    val state by flow.state.collectAsState()
    // Derived from the flow, never composable-local — Back/forward
    // navigation rebuilds this content.
    val usingCustom = state.servicesChoice == ServicesChoice.Custom

    // Tapping Recommended after a custom setup flips the choice while
    // persisted endpoints/pins STAY; that caveat stays reachable (as an
    // info symbol) whenever the tap matters.
    var sawCustomHere by remember { mutableStateOf(false) }
    LaunchedEffect(usingCustom) {
        if (usingCustom) sawCustomHere = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ServicesCard(
            selected = !usingCustom,
            label = stringResource(OnboardingR.string.onboarding_services_recommended_title) + ". " +
                stringResource(OnboardingR.string.onboarding_services_recommended_subtitle),
            testTag = "onboarding.services.recommended",
            onClick = {
                // An explicit affirmative pick of the recommended setup
                // is a consent — recording over the seed is a no-op.
                flow.recordServicesChoice(ServicesChoice.Recommended)
                flow.recordOutcome(StepOutcome.Consented(null))
            },
        ) {
            // The recommended set as its four seats — delivery, media,
            // group integrity, directory: the fixed promise the Done
            // step's tiles then show live.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf(
                    Icons.Filled.SettingsInputAntenna,
                    Icons.Filled.PhotoLibrary,
                    Icons.Filled.Verified,
                    Icons.Filled.Search,
                ).forEach { icon ->
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
            }
        }

        ServicesCard(
            selected = usingCustom,
            label = stringResource(OnboardingR.string.onboarding_services_custom_title) + ". " +
                stringResource(OnboardingR.string.onboarding_services_custom_subtitle),
            testTag = "onboarding.services.custom",
            onClick = onOpenHub,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }

        if (usingCustom || sawCustomHere) {
            SymbolHint(
                icon = Icons.Filled.Info,
                label = stringResource(OnboardingR.string.onboarding_hub_use_recommended_footnote),
                modifier = Modifier.testTag("onboarding.services.stays_configured"),
            )
        }
    }
}

@Composable
private fun ServicesCard(
    selected: Boolean,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                shape = shape,
            )
            // A mutually exclusive pair — selectable (not a bare
            // clickable) so TalkBack reads "selected, radio button".
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 24.dp, horizontal = 16.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
        // Stands in for the old "Selected" chip.
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-14).dp)
                    .size(20.dp),
            )
        }
    }
}

// ── Recovery phrase ───────────────────────────────────────────────

/**
 * "Save your recovery phrase" — the identity step's twin (iOS parity):
 * one life preserver, centred, badged at the same corner — an eye before
 * the words were seen, a pencil once revealed (write them down), a green
 * check once verified. Tapping it opens the SAME
 * [RecoveryPhraseBackupScreen] Settings uses, as a full-screen overlay.
 *
 * The primary (✓, "I've written it down") stays disabled until the phrase
 * was actually REVEALED; the alarm clock (the skip, "Remind me later") is
 * the honest escape.
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
    val restored = state.identityOrigin == IdentityOrigin.Restored

    val revealLabel = stringResource(
        if (sawPhrase) OnboardingR.string.onboarding_recovery_view_again else OnboardingR.string.onboarding_recovery_reveal,
    )
    val statusLabel = stringResource(
        when (backup) {
            RecoveryBackupState.Verified -> OnboardingR.string.onboarding_recovery_status_verified
            RecoveryBackupState.Revealed -> OnboardingR.string.onboarding_recovery_status_revealed
            RecoveryBackupState.None -> OnboardingR.string.onboarding_recovery_status_none
        },
    ) + ". " + stringResource(
        when (backup) {
            RecoveryBackupState.Verified -> OnboardingR.string.onboarding_recovery_body_verified
            RecoveryBackupState.Revealed ->
                if (restored) OnboardingR.string.onboarding_recovery_body_revealed_restored else OnboardingR.string.onboarding_recovery_body_revealed
            RecoveryBackupState.None ->
                if (restored) OnboardingR.string.onboarding_recovery_body_none_restored else OnboardingR.string.onboarding_recovery_body_none
        },
    )
    val tint = if (backedUp) SettingsTile.Green else MaterialTheme.colorScheme.primary

    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        FilledTonalIconButton(
            onClick = onReveal,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = tint.copy(alpha = 0.16f),
                contentColor = tint,
            ),
            modifier = Modifier
                .size(150.dp)
                .semantics { contentDescription = revealLabel }
                // onym:allow-secret-read: a semantics test tag — the
                // mandated onboarding.recoveryPhrase.* vocabulary.
                .testTag("onboarding.recoveryPhrase.reveal"),
        ) {
            Icon(Icons.Filled.Support, contentDescription = null, modifier = Modifier.size(72.dp))
        }
        // Outside the button so it stays its own node: what to do next,
        // then that it's done.
        StatusBadge(
            status = when (backup) {
                RecoveryBackupState.Verified -> TileStatus.Done
                RecoveryBackupState.Revealed -> TileStatus.Pending
                RecoveryBackupState.None -> TileStatus.Look
            },
            size = 44.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .semantics { contentDescription = statusLabel }
                // onym:allow-secret-read: a semantics test tag — see above.
                .testTag("onboarding.recoveryPhrase.status"),
        )
    }
}

/** A tile's or symbol's state, badged on its corner. */
private enum class TileStatus { Done, Pending, Empty, Look }

/** ✓ green (agreed / configured), ✎ amber (started), – grey (left empty),
 *  👁 blue (go and look). */
@Composable
private fun StatusBadge(status: TileStatus, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val (icon: ImageVector, color: androidx.compose.ui.graphics.Color) = when (status) {
        TileStatus.Done -> Icons.Filled.Check to SettingsTile.Green
        TileStatus.Pending -> Icons.Filled.Edit to SettingsTile.Amber
        TileStatus.Empty -> Icons.Filled.Remove to SettingsTile.Gray
        TileStatus.Look -> Icons.Filled.Visibility to MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(3.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(size * 0.55f))
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

    // The VM stays Activity-scoped (so it still gets a real
    // onCleared), but it is scrubbed on EVERY exit from this overlay
    // — dismiss, walk completion, rotation. Without this the retained
    // instance would keep Step.Reveal(phrase = <mnemonic>) alive for
    // the rest of the walk and beyond, and "View it again" would
    // re-render the words with no biometric re-auth. reset() drops
    // the phrase + cached identity and rewinds to Intro, so every
    // re-entry crosses the biometric gate again. The flow-held
    // recoveryBackupState (the status card) deliberately survives —
    // that is progress, not secret material.
    DisposableEffect(viewModel) {
        onDispose { viewModel.reset() }
    }

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

/** One reviewable thing on the Done step — a seat, the mandate, or the phrase. */
private data class DoneTile(
    val id: String,
    val icon: ImageVector,
    val status: TileStatus,
    val title: String,
    val value: String,
    val detail: String?,
    val trailing: String?,
)

/**
 * The review, in symbols (iOS parity): everything the walk set up or
 * agreed to, as one-size tiles in balanced rows — up to three a row, each
 * row centred — badged ✓ / ✎ / –. Read live from the repositories, not
 * the walk's outcomes: what the app will actually use is the checkable
 * claim. Tapping a tile opens what it stands for in words — the chosen
 * name and the checkable detail (URL, key fingerprint, manifest hash) —
 * and the recovery tile can run the backup again ([onBackup]).
 */
@Composable
private fun DoneStepContent(
    dependencies: AppDependencies,
    onboarding: OnboardingUiDependencies,
    flow: OnboardingFlow,
    onBackup: () -> Unit,
) {
    val state by flow.state.collectAsState()
    val nostr by dependencies.nostrRelaysFlow.collectAsStateWithLifecycle()
    val blossom by dependencies.blossomServersFlow.collectAsStateWithLifecycle()
    val relayer by onboarding.relayerState.collectAsStateWithLifecycle()
    val discoveryState = dependencies.discovery?.stateFlow?.let { it.collectAsStateWithLifecycle().value }
    var mandate by remember { mutableStateOf<app.onym.android.moderation.MandateRecord?>(null) }
    LaunchedEffect(dependencies.moderation) {
        mandate = dependencies.moderation?.repository?.activeMandateRecord()
    }
    var reading by remember { mutableStateOf<String?>(null) }

    @Composable
    fun count(n: Int) = n.takeIf { it > 0 }?.let {
        pluralStringResource(OnboardingR.plurals.onboarding_done_services_count, it, it)
    }

    val tiles = buildList {
        val nostrFirst = nostr.endpoints.firstOrNull()
        add(
            DoneTile(
                id = "messageTransport", icon = Icons.Filled.SettingsInputAntenna,
                status = if (nostrFirst != null) TileStatus.Done else TileStatus.Empty,
                title = stringResource(OnboardingR.string.onboarding_hub_row_message_title),
                value = nostrFirst?.name ?: stringResource(OnboardingR.string.onboarding_done_none_configured),
                detail = nostrFirst?.url, trailing = count(nostr.endpoints.size),
            ),
        )
        val blossomFirst = blossom.endpoints.firstOrNull()
        add(
            DoneTile(
                id = "blobTransport", icon = Icons.Filled.PhotoLibrary,
                status = if (blossomFirst != null) TileStatus.Done else TileStatus.Empty,
                title = stringResource(OnboardingR.string.onboarding_hub_row_media_title),
                value = blossomFirst?.name ?: stringResource(OnboardingR.string.onboarding_done_none_configured),
                detail = blossomFirst?.url, trailing = count(blossom.endpoints.size),
            ),
        )
        val relayers = relayer.configuration.endpoints
        add(
            DoneTile(
                // Empty means the published defaults install on completion.
                id = "notary", icon = Icons.Filled.Verified, status = TileStatus.Done,
                title = stringResource(OnboardingR.string.onboarding_hub_row_group_title),
                value = relayers.firstOrNull()?.name ?: stringResource(OnboardingR.string.onboarding_services_value_group),
                detail = relayers.firstOrNull()?.url, trailing = count(relayers.size),
            ),
        )
        // Pinned: the fingerprint IS the checkable detail. Not pinned: an
        // explicit "Not confirmed" and no detail — a URL there would read
        // like a confirmed source's fingerprint.
        val sources = discoveryState?.sources.orEmpty()
        val pinned = sources.firstOrNull { it.pinnedOperatorKeyHex != null }
        val shown = pinned ?: sources.firstOrNull()
        if (discoveryState != null && shown != null) {
            add(
                DoneTile(
                    id = "discovery", icon = Icons.Filled.Search,
                    status = if (pinned != null) TileStatus.Done else TileStatus.Pending,
                    title = stringResource(OnboardingR.string.onboarding_hub_row_directory_title),
                    value = shown.label, detail = pinned?.operatorKeyFingerprint,
                    trailing = if (pinned != null) {
                        pluralStringResource(OnboardingR.plurals.onboarding_done_sources_count, sources.size, sources.size)
                    } else {
                        stringResource(OnboardingR.string.onboarding_done_directory_not_confirmed)
                    },
                ),
            )
        }
        if (dependencies.moderation != null) {
            val record = mandate
            add(
                DoneTile(
                    id = "moderation", icon = Icons.Filled.Shield,
                    status = if (record != null) TileStatus.Done else TileStatus.Empty,
                    title = stringResource(OnboardingR.string.onboarding_moderation_title),
                    value = record?.authorityName ?: stringResource(OnboardingR.string.onboarding_done_none_configured),
                    detail = record?.mandate?.manifestHash,
                    trailing = record?.let { stringResource(OnboardingR.string.onboarding_done_agreed) },
                ),
            )
        }
        val restored = state.identityOrigin == IdentityOrigin.Restored
        add(
            when (state.recoveryBackupState) {
                RecoveryBackupState.Verified -> DoneTile(
                    "recoveryPhrase", Icons.Filled.Support, TileStatus.Done,
                    stringResource(OnboardingR.string.onboarding_recovery_title),
                    stringResource(OnboardingR.string.onboarding_recovery_status_verified), null, null,
                )
                RecoveryBackupState.Revealed -> DoneTile(
                    "recoveryPhrase", Icons.Filled.Support, TileStatus.Pending,
                    stringResource(OnboardingR.string.onboarding_done_nudge_revealed_title),
                    stringResource(
                        if (restored) OnboardingR.string.onboarding_done_nudge_revealed_body_restored else OnboardingR.string.onboarding_done_nudge_revealed_body,
                    ),
                    null, null,
                )
                RecoveryBackupState.None -> DoneTile(
                    "recoveryPhrase", Icons.Filled.Support, TileStatus.Empty,
                    stringResource(OnboardingR.string.onboarding_done_nudge_full_title),
                    stringResource(
                        if (restored) OnboardingR.string.onboarding_done_nudge_full_body_restored else OnboardingR.string.onboarding_done_nudge_full_body,
                    ),
                    null, null,
                )
            },
        )
    }

    val hint = stringResource(OnboardingR.string.onboarding_done_tile_hint)
    Column(
        modifier = Modifier.testTag("onboarding.done.summary"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        balancedRows(tiles).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { tile ->
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(role = Role.Button) { reading = tile.id }
                            .semantics {
                                contentDescription = listOfNotNull(tile.title, tile.value, tile.trailing).joinToString(", ")
                                onClick(label = hint) { reading = tile.id; true }
                            }
                            .testTag(if (tile.id == "recoveryPhrase") "onboarding.done.backup_nudge" else "onboarding.done.tile.${tile.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            tile.icon,
                            contentDescription = null,
                            tint = if (tile.status == TileStatus.Empty) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp),
                        )
                        StatusBadge(tile.status, 24.dp, Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = (-4).dp))
                    }
                }
            }
        }
    }

    // Looked up live, not snapshotted: a backup run from the sheet must
    // be reflected while it is still open.
    tiles.firstOrNull { it.id == reading }?.let { tile ->
        DoneTileSheet(
            tile = tile,
            onDismiss = { reading = null },
            onBackup = if (tile.id == "recoveryPhrase") {
                {
                    reading = null
                    onBackup()
                }
            } else {
                null
            },
            backupLabel = stringResource(
                if (state.recoveryBackupState == RecoveryBackupState.None) {
                    OnboardingR.string.onboarding_recovery_reveal
                } else {
                    OnboardingR.string.onboarding_done_backup_again
                },
            ),
            backupIcon = if (state.recoveryBackupState == RecoveryBackupState.None) Icons.Filled.Visibility else Icons.Filled.Refresh,
        )
    }
}

/** Balanced rows: 5 tiles are 3 + 2, 4 are 2 + 2 — never a full row over
 *  a lone straggler when an even split exists. */
private fun <T> balancedRows(items: List<T>, perRow: Int = 3): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val rows = (items.size + perRow - 1) / perRow
    val base = items.size / rows
    var extra = items.size % rows
    var index = 0
    return List(rows) {
        val size = base + if (extra-- > 0) 1 else 0
        items.subList(index, index + size).also { index += size }
    }
}

/** What a tile stands for, in words — the one place this step spells
 *  anything out, and only when asked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoneTileSheet(
    tile: DoneTile,
    onDismiss: () -> Unit,
    onBackup: (() -> Unit)?,
    backupLabel: String,
    backupIcon: ImageVector,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag("onboarding.done.detail"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                StatusBadge(tile.status, 20.dp, Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp))
            }
            Text(tile.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
            Text(tile.value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            tile.trailing?.let {
                Text(it, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = SettingsTile.Green)
            }
            tile.detail?.let {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (onBackup != null) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(
                    onClick = onBackup,
                    modifier = Modifier
                        .height(48.dp)
                        .widthIn(min = 120.dp)
                        .semantics { contentDescription = backupLabel }
                        .testTag("onboarding.done.backup_again"),
                ) { Icon(backupIcon, contentDescription = null) }
            }
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
