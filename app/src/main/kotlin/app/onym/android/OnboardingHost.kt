package app.onym.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.DiscoverySource
import app.onym.android.onboarding.OnboardingFlow
import app.onym.android.onboarding.OnboardingScreen
import app.onym.android.onboarding.OnboardingStep
import app.onym.android.onboarding.StepOutcome
import app.onym.android.settings.DiscoveryCatalogCard
import app.onym.android.settings.DiscoveryChip
import app.onym.android.settings.DiscoverySettingsViewModel
import app.onym.android.settings.ModuleConsentScreen
import app.onym.android.settings.ModuleConsentViewModel
import app.onym.android.strings.R
import kotlinx.coroutines.launch

/**
 * The first-launch onboarding presentation: mounts the :onboarding
 * module's [OnboardingScreen] full-screen with system back blocked,
 * injects the real step contents (discovery TOFU confirm, per-seat
 * catalog pickers) through the content slot, and hosts the
 * module-consent flow as a full-screen overlay above the walk.
 *
 * Reuses the A4 surfaces rather than re-implementing them: the
 * consent flow is the exact [ModuleConsentScreen] Settings uses (with
 * the same pre-bound per-seat apply closures — the blob seat's apply
 * runs `addEndpoint(makeActive = true)` from #211), the TOFU confirm
 * drives the same [DiscoverySettingsViewModel] add-source path as
 * Settings → Discovery, and the catalog rows are the shared
 * [DiscoveryCatalogCard].
 */
/**
 * Retains the [OnboardingFlow] across configuration changes: rotation
 * or a locale/theme switch recreates the Activity, and a `remember`ed
 * flow would restart the walk at Welcome. Holding it in a ViewModel
 * keeps the exact same state-machine instance — none of #210's
 * semantics change (complete() only from Done, advance gating,
 * Mutex-guarded start(), and partial progress still deliberately
 * dies with the PROCESS, per the flow's KDoc). Its [viewModelScope]
 * doubles as the home for step-content repository writes that must
 * outlive the step they were tapped on.
 */
internal class OnboardingHostViewModel(val flow: OnboardingFlow) : ViewModel()

@Composable
internal fun OnboardingHost(
    dependencies: AppDependencies,
    onboarding: OnboardingUiDependencies,
) {
    val hostViewModel: OnboardingHostViewModel = viewModel(
        key = "onboarding.host",
        factory = viewModelFactory {
            initializer { OnboardingHostViewModel(onboarding.makeFlow()) }
        },
    )
    val flow = hostViewModel.flow
    val state by flow.state.collectAsState()

    // Full-screen and back-blocked: the only exits are Done's primary
    // action and the per-step Skip affordances.
    BackHandler(enabled = true) {}

    LaunchedEffect(state.completed) {
        if (state.completed) onboarding.onCompleted()
    }

    // Module-consent overlay: the tapped catalog entry. Held here
    // (not per-step) so the overlay survives the step content
    // recomposing beneath it; the ViewModel itself is created below
    // via viewModel(key) so it is properly store-scoped and cleared
    // — never orphaned with a running start() fetch.
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
                        OnboardingStep.DiscoveryConfirm -> ({
                            DiscoveryConfirmStepContent(
                                dependencies = dependencies,
                                flow = flow,
                            )
                        })
                        OnboardingStep.MessageTransport,
                        OnboardingStep.BlobTransport,
                        OnboardingStep.Notary -> ({
                            SeatStepContent(
                                step = step,
                                dependencies = dependencies,
                                onboarding = onboarding,
                                flow = flow,
                                writeScope = hostViewModel.viewModelScope,
                                onOpenConsent = { entry -> consentEntry = entry },
                            )
                        })
                        // Welcome and Done keep the module built-ins.
                        else -> null
                    }
                },
            )

            consentEntry?.let { entry ->
                val discovery = dependencies.discovery
                if (discovery != null) {
                    // The A4 consent surface, verbatim, as a
                    // full-screen overlay. viewModel(key-per-entry)
                    // scopes the VM to the host's store — cleared
                    // with it, reused on a reopen (a consented
                    // entry's Done state is exactly what a revisit
                    // should show). Closing records a Consented
                    // outcome for the current step exactly when the
                    // flow reached Done (pin + selection + apply all
                    // landed).
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
                            if (consented) {
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

// ── Discovery confirm step ────────────────────────────────────────

/**
 * Step 2 content: TOFU-confirm the seeded default discovery provider
 * (fingerprint hero → pin), or add your own provider URL — both
 * through the same [DiscoverySettingsViewModel] add-source path the
 * Settings flow uses, so nothing is trusted before the fingerprint
 * confirm. Pinning records [StepOutcome.Consented] (no component
 * identity — this consent is about the provider, not a seat module).
 */
@Composable
private fun DiscoveryConfirmStepContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
) {
    val discovery = dependencies.discovery ?: return
    // Store-scoped (same pattern as RootScreen's destinations), NOT
    // remember{}: a remembered androidx ViewModel never gets
    // onCleared(), so its Eagerly-started snapshots collector would
    // leak once per back→forward pass through this step.
    val viewModel: DiscoverySettingsViewModel = viewModel(
        key = "onboarding.discoverySettings",
        factory = viewModelFactory {
            initializer { discovery.makeDiscoverySettingsViewModel() }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manualMode by remember { mutableStateOf(false) }

    val pinnedSource = state.discovery.sources.firstOrNull { it.pinnedOperatorKeyHex != null }
    val defaultSource = state.discovery.sources.firstOrNull { it.pinnedOperatorKeyHex == null }

    Column(Modifier.fillMaxWidth()) {
        when {
            // A pinned provider exists (confirmed just now, or on a
            // revisit via Back): the confirmed state.
            pinnedSource != null && state.addPhase !is DiscoverySettingsViewModel.AddPhase.Confirming -> {
                PinnedProviderCard(source = pinnedSource)
                LaunchedEffect(pinnedSource.providerId) {
                    flow.recordOutcome(StepOutcome.Consented(null))
                }
            }

            state.addPhase is DiscoverySettingsViewModel.AddPhase.Fetching -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("onboarding.discoveryConfirm.fetching"),
                    )
                }
            }

            state.addPhase is DiscoverySettingsViewModel.AddPhase.Confirming -> {
                val pending =
                    (state.addPhase as DiscoverySettingsViewModel.AddPhase.Confirming).pending
                // Fingerprint hero — same copy + format as the
                // Settings TOFU screen (AddDiscoveryProviderScreen).
                SettingsFootnote(stringResource(R.string.discovery_confirm_intro))
                SettingsSectionLabel(stringResource(R.string.discovery_section_fingerprint))
                SettingsCard {
                    Text(
                        text = DiscoverySource.fingerprint(pending.operatorKeyHex),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                            .testTag("onboarding.discoveryConfirm.fingerprint"),
                    )
                }
                SettingsFootnote(stringResource(R.string.discovery_fingerprint_footnote))
                SettingsSectionLabel(stringResource(R.string.discovery_section_provider))
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.discovery_field_provider),
                        subtitle = pending.manifest.providerId,
                        subtitleMono = true,
                        insetHairline = 16.dp,
                    )
                    SettingsHairline(insetStart = 16.dp)
                    SettingsRow(
                        title = stringResource(R.string.discovery_field_manifest_url),
                        subtitle = pending.url,
                        subtitleMono = true,
                        isLast = true,
                        insetHairline = 16.dp,
                    )
                }
                ActionColumn {
                    Button(
                        onClick = viewModel::tappedConfirmAdd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding.discoveryConfirm.confirm"),
                    ) { Text(stringResource(R.string.discovery_confirm_pin)) }
                    TextButton(
                        onClick = viewModel::tappedCancelPreview,
                        modifier = Modifier.testTag("onboarding.discoveryConfirm.cancel"),
                    ) { Text(stringResource(R.string.back)) }
                }
            }

            manualMode -> {
                SettingsFootnote(stringResource(R.string.discovery_add_intro))
                ActionColumn {
                    OutlinedTextField(
                        value = state.addDraft,
                        onValueChange = viewModel::addDraftChanged,
                        placeholder = { Text("https://discovery.example.com/manifest.json") },
                        singleLine = true,
                        isError = state.addError != null,
                        textStyle = MaterialTheme.typography.bodyMedium
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding.discoveryConfirm.url_field"),
                    )
                    state.addError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("onboarding.discoveryConfirm.error"),
                        )
                    }
                    Button(
                        onClick = viewModel::tappedFetchProvider,
                        enabled = state.addDraft.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding.discoveryConfirm.fetch"),
                    ) { Text(stringResource(R.string.discovery_add_fetch)) }
                    if (defaultSource != null) {
                        TextButton(
                            onClick = { manualMode = false },
                            modifier = Modifier.testTag("onboarding.discoveryConfirm.use_default"),
                        ) { Text(stringResource(R.string.onboarding_discovery_use_default)) }
                    }
                }
            }

            // The seeded default hasn't hydrated yet (the discovery
            // repository bootstraps in its own launch): a loading
            // state instead of a dead-end hero with no Review action.
            // "Add your own provider" stays reachable as the escape
            // hatch.
            defaultSource == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("onboarding.discoveryConfirm.loading"),
                    )
                }
                ActionColumn {
                    TextButton(
                        onClick = { manualMode = true },
                        modifier = Modifier.testTag("onboarding.discoveryConfirm.add_own"),
                    ) { Text(stringResource(R.string.onboarding_discovery_add_own)) }
                }
            }

            else -> {
                // Hero: the seeded default provider, unpinned until
                // the user reviews its fingerprint.
                SettingsSectionLabel(
                    stringResource(R.string.onboarding_discovery_section_provider),
                )
                SettingsCard {
                    SettingsRow(
                        title = defaultSource.label,
                        subtitle = defaultSource.url,
                        subtitleMono = true,
                        isLast = true,
                        insetHairline = 16.dp,
                        modifier = Modifier.testTag("onboarding.discoveryConfirm.default_provider"),
                    )
                }
                SettingsFootnote(stringResource(R.string.discovery_confirm_intro))
                ActionColumn {
                    state.addError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("onboarding.discoveryConfirm.error"),
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.addDraftChanged(defaultSource.url)
                            viewModel.tappedFetchProvider()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding.discoveryConfirm.review"),
                    ) {
                        Text(stringResource(R.string.onboarding_discovery_review_fingerprint))
                    }
                    TextButton(
                        onClick = { manualMode = true },
                        modifier = Modifier.testTag("onboarding.discoveryConfirm.add_own"),
                    ) { Text(stringResource(R.string.onboarding_discovery_add_own)) }
                }
            }
        }
    }
}

@Composable
private fun PinnedProviderCard(source: DiscoverySource) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag("onboarding.discoveryConfirm.pinned"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = SettingsTile.Green,
            modifier = Modifier.size(44.dp),
        )
        DiscoveryChip(
            stringResource(R.string.onboarding_discovery_chip_pinned),
            SettingsTile.Green,
        )
        Text(
            stringResource(R.string.onboarding_discovery_pinned_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            source.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        source.operatorKeyFingerprint?.let { fingerprint ->
            Text(
                fingerprint,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.onboarding_discovery_pinned_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

// ── Seat steps (messageTransport / blobTransport / notary) ────────

/**
 * Steps 3–5 content: the seat's current defaults (informational for
 * the seeded Nostr/Blossom lists, tappable published relayers for the
 * notary seat — a legacy add takes no consent flow), plus the "FROM
 * CATALOG" card routing into the module-consent overlay. Skipping
 * keeps the defaults; the footnote says so.
 */
@Composable
private fun SeatStepContent(
    step: OnboardingStep,
    dependencies: AppDependencies,
    onboarding: OnboardingUiDependencies,
    flow: OnboardingFlow,
    /** Scope for repository writes (the notary legacy add) — the
     *  host ViewModel's scope, so tapping Add and immediately
     *  advancing (or rotating) can't cancel the write mid-flight the
     *  way a rememberCoroutineScope-launched one would be. */
    writeScope: CoroutineScope,
    onOpenConsent: (AttributedCatalogEntry) -> Unit,
) {
    val seatType = when (step) {
        OnboardingStep.MessageTransport -> DiscoveryBackedKnownNostrRelaysFetcher.SEAT_TYPE
        OnboardingStep.BlobTransport -> DiscoveryBackedKnownBlossomServersFetcher.SEAT_TYPE
        else -> DiscoveryBackedKnownRelayersFetcher.SEAT_TYPE
    }

    Column(Modifier.fillMaxWidth()) {
        when (step) {
            OnboardingStep.MessageTransport -> {
                val config by dependencies.nostrRelaysFlow.collectAsStateWithLifecycle()
                if (config.endpoints.isNotEmpty()) {
                    SettingsSectionLabel(
                        stringResource(R.string.onboarding_seat_section_defaults),
                    )
                    SettingsCard {
                        config.endpoints.forEachIndexed { idx, endpoint ->
                            SettingsRow(
                                title = endpoint.name,
                                subtitle = endpoint.url,
                                subtitleMono = true,
                                trailing = {
                                    if (endpoint.isDefault) {
                                        DiscoveryChip(
                                            stringResource(R.string.default_pill),
                                            MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                isLast = idx == config.endpoints.lastIndex,
                                insetHairline = 16.dp,
                                modifier = Modifier.testTag(
                                    "onboarding.messageTransport.default.${endpoint.url}",
                                ),
                            )
                        }
                    }
                }
            }

            OnboardingStep.BlobTransport -> {
                val config by dependencies.blossomServersFlow.collectAsStateWithLifecycle()
                if (config.endpoints.isNotEmpty()) {
                    SettingsSectionLabel(
                        stringResource(R.string.onboarding_seat_section_defaults),
                    )
                    SettingsCard {
                        config.endpoints.forEachIndexed { idx, endpoint ->
                            SettingsRow(
                                title = endpoint.name,
                                subtitle = endpoint.url,
                                subtitleMono = true,
                                trailing = {
                                    // Uploads/downloads target the
                                    // FIRST endpoint (#211): badge it.
                                    if (idx == 0) {
                                        DiscoveryChip(
                                            stringResource(R.string.blossom_active_pill),
                                            MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                },
                                isLast = idx == config.endpoints.lastIndex,
                                insetHairline = 16.dp,
                                modifier = Modifier.testTag(
                                    "onboarding.blobTransport.default.${endpoint.url}",
                                ),
                            )
                        }
                    }
                }
            }

            else -> {
                // Notary: the published relayer list is fetched even
                // while auto-populate is suppressed (the #211 seam
                // defers the install, not the fetch) — rows add
                // without a consent flow, exactly like the Settings
                // picker's known list.
                val relayerState by onboarding.relayerState.collectAsStateWithLifecycle()
                val configuredUrls = relayerState.configuration.endpoints.map { it.url }.toSet()
                if (relayerState.knownRelayers.isNotEmpty()) {
                    SettingsSectionLabel(
                        stringResource(R.string.onboarding_seat_section_published),
                    )
                    SettingsCard {
                        relayerState.knownRelayers.forEachIndexed { idx, endpoint ->
                            val configured = endpoint.url in configuredUrls
                            SettingsRow(
                                title = endpoint.name,
                                subtitle = endpoint.url,
                                subtitleMono = true,
                                trailing = {
                                    if (configured) {
                                        DiscoveryChip(
                                            stringResource(R.string.onboarding_seat_added_chip),
                                            SettingsTile.Green,
                                        )
                                    } else {
                                        TextButton(
                                            onClick = {
                                                writeScope.launch {
                                                    onboarding.addRelayerEndpoint(endpoint)
                                                    // recordOutcome targets the
                                                    // CURRENT step — if the user
                                                    // already advanced past
                                                    // notary while the write
                                                    // ran, don't stamp the next
                                                    // step's outcome.
                                                    if (flow.state.value.step ==
                                                        OnboardingStep.Notary
                                                    ) {
                                                        flow.recordOutcome(
                                                            StepOutcome.Consented(null),
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.testTag(
                                                "onboarding.notary.add.${endpoint.url}",
                                            ),
                                        ) {
                                            Text(stringResource(R.string.onboarding_seat_add))
                                        }
                                    }
                                },
                                isLast = idx == relayerState.knownRelayers.lastIndex,
                                insetHairline = 16.dp,
                                modifier = Modifier.testTag(
                                    "onboarding.notary.known.${endpoint.url}",
                                ),
                            )
                        }
                    }
                }
            }
        }

        // "FROM CATALOG": discovery-sourced modules for this seat,
        // present once a provider is pinned and its catalog fetched.
        // The shared card routes into the module-consent overlay.
        val (entries, consents) = rememberSeatCatalog(
            dependencies = dependencies,
            seatType = seatType,
        )
        if (entries.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.discovery_catalog_section))
            DiscoveryCatalogCard(
                entries = entries,
                consentByComponentId = consents,
                onEntryClick = onOpenConsent,
            )
            SettingsFootnote(stringResource(R.string.discovery_catalog_footnote))
        } else {
            SettingsFootnote(stringResource(R.string.onboarding_seat_no_catalog))
        }
        SettingsFootnote(stringResource(R.string.onboarding_seat_defaults_footnote))
    }
}

/** Shared inset column for the in-content action buttons. */
@Composable
private fun ActionColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
