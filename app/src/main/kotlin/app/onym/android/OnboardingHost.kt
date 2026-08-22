package app.onym.android

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
    val backupShown = backupVisible && state.step == OnboardingStep.RecoveryPhrase
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
        if (state.step != OnboardingStep.RecoveryPhrase) backupVisible = false
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
                            WelcomeStepContent(
                                // Same two-part gate as the overlay's
                                // mount: allowed AND the walk has
                                // never moved past Welcome.
                                restoreAvailable = restoreAllowed &&
                                    state.neverLeftWelcome,
                                onRestore = { restoreVisible = true },
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
 * Brand framing: the OnymMark over three "yours, not ours" bullets,
 * plus — iOS parity — the "I have a recovery phrase" entry beneath
 * the card. [restoreAvailable] gates the entry: it is hidden when the
 * wiring is absent, when the walk runs over an identity that
 * [app.onym.android.identity.IdentityRepository.restore] would wipe
 * (restart walks, grandfathered users — see
 * [OnboardingUiDependencies.restoreIdentityAllowed]), and once the
 * walk has advanced past Welcome even if the user comes Back
 * ([OnboardingFlow.State.neverLeftWelcome]).
 */
@Composable
private fun WelcomeStepContent(
    restoreAvailable: Boolean,
    onRestore: () -> Unit,
) {
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

        if (restoreAvailable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier.testTag("onboarding.welcome.restore"),
                ) {
                    Text(
                        text = stringResource(
                            OnboardingR.string.onboarding_welcome_restore_entry,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
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
    /** Copy flip for a restored identity (iOS identityOrigin parity):
     *  "created"/"generated" would be a lie over keys the user just
     *  brought here from a recovery phrase. */
    restored: Boolean,
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
                title = stringResource(
                    if (restored) {
                        OnboardingR.string.onboarding_identity_row_key_title_restored
                    } else {
                        OnboardingR.string.onboarding_identity_row_key_title
                    },
                ),
                subtitle = stringResource(OnboardingR.string.onboarding_identity_row_key_subtitle),
            )
            SettingsHairline(insetStart = 52.dp)
            IdentityChecklistRow(
                phase = phase,
                title = stringResource(
                    if (restored) {
                        OnboardingR.string.onboarding_identity_row_phrase_title_restored
                    } else {
                        OnboardingR.string.onboarding_identity_row_phrase_title
                    },
                ),
                subtitle = stringResource(
                    if (restored) {
                        OnboardingR.string.onboarding_identity_row_phrase_subtitle_restored
                    } else {
                        OnboardingR.string.onboarding_identity_row_phrase_subtitle
                    },
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
                    // A real button: Role.Button semantics + the M3
                    // 48dp minimum touch target (a bare clickable
                    // Text had neither).
                    TextButton(
                        onClick = { attempt += 1 },
                        modifier = Modifier.testTag("onboarding.identity.retry"),
                    ) {
                        Text(
                            text = stringResource(
                                OnboardingR.string.onboarding_identity_retry,
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
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
 * The moderation consent step: the reviewed manifest snapshot with
 * Agree wired to `ModerationRepository.consent`. Outcome-gated like
 * every core step — Consented on success; Unavailable when the
 * directory offers nothing (Continue acknowledges, per the flow's
 * mandatory/Unavailable arithmetic — opting out of moderation is not
 * a thing, but an empty directory cannot demand consent). A null
 * `dependencies.moderation` under an enabled step is unreachable:
 * the flow only enters the step when the seat is wired.
 */
@Composable
private fun ModerationStepContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
) {
    // The step only enters the walk when the seat is wired (both read
    // the same moderationUi), but if that invariant ever breaks, a
    // silent `?: return` renders an EMPTY outcome-gated, unskippable,
    // back-blocked step — the bricked wizard the old tripwire
    // existed to prevent. Fail fast and name the missing piece.
    val moderation = checkNotNull(dependencies.moderation) {
        "OnboardingStep.Moderation is in the walk but AppDependencies.moderation is null — " +
            "OnymApplication must construct the moderation dependencies whenever it enables " +
            "the step (moderationEnabled = moderationUi != null)"
    }
    val controller = remember { moderation.makeConsentController(true) }
    app.onym.android.moderation.ui.ModerationConsentContent(
        controller = controller,
        // The step scaffold already scrolls; the surface must not
        // nest its own unbounded scroll inside it.
        standalone = false,
        // Debug/emulator builds can never pass the backend's
        // classifier; without this the deterministic refusal
        // hard-blocks every local run at this step. Release binaries
        // pass false and keep the strict retry-only surface.
        debugSkipAllowed = app.onym.android.BuildConfig.DEBUG,
        onConsented = { record ->
            if (flow.state.value.step == OnboardingStep.Moderation) {
                flow.recordOutcome(StepOutcome.Consented(record.mandate.authority))
            }
            // The iOS `consentCompleted` invariant: a fresh gate
            // check immediately, so the gate reflects the mandate.
            moderation.gate.consentCompleted()
        },
        onUnavailableContinue = {
            if (flow.state.value.step == OnboardingStep.Moderation) {
                flow.recordOutcome(StepOutcome.Unavailable)
            }
            // Also defer the ROOT gate for this process: without it,
            // finishing the walk lands the user straight on the
            // full-screen NeedsConsent surface they just continued
            // past — the same unreachable authority, asked twice.
            moderation.gate.deferConsent()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

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

    // Parity with the hub's use-recommended caveat: tapping the
    // Recommended card after a custom setup flips the chip and
    // overwrites the outcome while persisted endpoints/pins STAY —
    // so the same stays-configured footnote must be visible at the
    // card whenever that tap matters: while Custom is selected, and
    // lingering after a flip back within this visit (the flow-held
    // choice alone can't say "was custom a moment ago").
    var sawCustomHere by remember { mutableStateOf(false) }
    LaunchedEffect(usingCustom) {
        if (usingCustom) sawCustomHere = true
    }

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

        if (usingCustom || sawCustomHere) {
            Text(
                text = stringResource(
                    OnboardingR.string.onboarding_hub_use_recommended_footnote,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("onboarding.services.stays_configured"),
            )
        }
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
            // The two cards are a mutually exclusive pair —
            // selectable (not a bare clickable) so TalkBack reads
            // "selected, radio button" and merges the card's text
            // into one announced node (selectable merges
            // descendants), mirroring the visual chip.
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
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
    // A restored identity's phrase may be 24 words — the "12 words"
    // copy must not survive over a 24-word reveal.
    val restored = state.identityOrigin == IdentityOrigin.Restored

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
                                if (restored) {
                                    OnboardingR.string.onboarding_recovery_body_revealed_restored
                                } else {
                                    OnboardingR.string.onboarding_recovery_body_revealed
                                }
                            RecoveryBackupState.None ->
                                if (restored) {
                                    OnboardingR.string.onboarding_recovery_body_none_restored
                                } else {
                                    OnboardingR.string.onboarding_recovery_body_none
                                }
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
            // The directory row must distinguish the same states the
            // hub does. Pinned: the fingerprint IS the checkable
            // detail (+ the source count). NOT pinned: an explicit
            // "Not confirmed" trailing and NO detail line — a URL
            // there would render indistinguishably from a confirmed
            // source's fingerprint. A pinned source wins the row
            // when several exist (it is the one refresh trusts).
            val sources = discoveryState?.sources.orEmpty()
            val pinnedSource = sources.firstOrNull { it.pinnedOperatorKeyHex != null }
            val displaySource = pinnedSource ?: sources.firstOrNull()
            if (discoveryState != null && displaySource != null) {
                SettingsHairline(insetStart = 52.dp)
                SummaryRow(
                    icon = Icons.Filled.Search,
                    title = stringResource(
                        OnboardingR.string.onboarding_hub_row_directory_title,
                    ),
                    value = displaySource.label,
                    detail = pinnedSource?.operatorKeyFingerprint,
                    trailing = if (pinnedSource != null) {
                        pluralStringResource(
                            OnboardingR.plurals.onboarding_done_sources_count,
                            sources.size,
                            sources.size,
                        )
                    } else {
                        stringResource(
                            OnboardingR.string.onboarding_done_directory_not_confirmed,
                        )
                    },
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
                    // The restored variants avoid the minted copy's
                    // "12 words" (the phrase may be 24) and its
                    // "created on this device / exists nowhere else"
                    // claims (the user brought it here).
                    val restored = state.identityOrigin == IdentityOrigin.Restored
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
                            when {
                                revealed && restored ->
                                    OnboardingR.string.onboarding_done_nudge_revealed_body_restored
                                revealed ->
                                    OnboardingR.string.onboarding_done_nudge_revealed_body
                                restored ->
                                    OnboardingR.string.onboarding_done_nudge_full_body_restored
                                else ->
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
