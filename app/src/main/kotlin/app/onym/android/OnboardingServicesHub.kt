package app.onym.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.onym.android.chain.RelayerConfiguration
import app.onym.android.chain.RelayerFetchStatus
import app.onym.android.chain.RelayerStrategy
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.DiscoverySource
import app.onym.android.onboarding.OnboardingFlow
import app.onym.android.onboarding.StepOutcome
import app.onym.android.settings.BlossomServerSettingsViewModel
import app.onym.android.settings.DiscoveryCatalogCard
import app.onym.android.settings.DiscoveryChip
import app.onym.android.settings.DiscoverySettingsViewModel
import app.onym.android.settings.NostrRelaySettingsViewModel
import app.onym.android.settings.RelayerSettingsViewModel
import app.onym.android.strings.R
import app.onym.android.onboarding.R as OnboardingR

// The "choose services myself" hub: a full-screen overlay above the
// services step, hosting its own nested NavHost. Each seat screen
// reuses the exact ViewModel the corresponding Settings screen
// drives, restructured to the redesigned shape: explainer intro,
// IN USE (only when non-empty), SUGGESTED BY YOUR DIRECTORIES
// (catalog), ADD YOUR OWN (custom URL).
//
// Back handling: the NavHost's own back dispatcher pops seat screens
// (it registers after — deeper than — the onboarding host's
// swallow-all BackHandler, so it wins while a seat screen is
// pushed). At the hub root there is nothing to pop and back falls
// through to the host's swallow: the hub's exits are Done and "Use
// the recommended setup instead", mirroring the iOS sheet's
// `interactiveDismissDisabled`.

private const val ROUTE_HUB = "hub"
private const val ROUTE_MESSAGE = "hub/messageDelivery"
private const val ROUTE_MEDIA = "hub/mediaDelivery"
private const val ROUTE_DIRECTORY = "hub/directory"
private const val ROUTE_GROUP = "hub/groupIntegrity"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingServicesHubOverlay(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
    onOpenConsent: (AttributedCatalogEntry) -> Unit,
    onUseRecommended: () -> Unit,
    onDone: () -> Unit,
) {
    val navController = rememberNavController()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        NavHost(navController = navController, startDestination = ROUTE_HUB) {
            composable(ROUTE_HUB) {
                HubScreen(
                    onOpenSeat = { route -> navController.navigate(route) },
                    onUseRecommended = onUseRecommended,
                    onDone = onDone,
                )
            }
            composable(ROUTE_MESSAGE) {
                SeatScreen(
                    titleRes = OnboardingR.string.onboarding_hub_row_message_title,
                    backTag = "onboarding.services.messageDelivery.back",
                    onBack = { navController.popBackStack() },
                ) {
                    MessageDeliveryContent(
                        dependencies = dependencies,
                        flow = flow,
                        onOpenConsent = onOpenConsent,
                    )
                }
            }
            composable(ROUTE_MEDIA) {
                SeatScreen(
                    titleRes = OnboardingR.string.onboarding_hub_row_media_title,
                    backTag = "onboarding.services.mediaDelivery.back",
                    onBack = { navController.popBackStack() },
                ) {
                    MediaDeliveryContent(
                        dependencies = dependencies,
                        flow = flow,
                        onOpenConsent = onOpenConsent,
                    )
                }
            }
            composable(ROUTE_DIRECTORY) {
                SeatScreen(
                    titleRes = OnboardingR.string.onboarding_hub_row_directory_title,
                    backTag = "onboarding.services.directory.back",
                    onBack = { navController.popBackStack() },
                ) {
                    DirectoryContent(
                        dependencies = dependencies,
                        flow = flow,
                    )
                }
            }
            composable(ROUTE_GROUP) {
                SeatScreen(
                    titleRes = OnboardingR.string.onboarding_hub_row_group_title,
                    backTag = "onboarding.services.groupIntegrity.back",
                    onBack = { navController.popBackStack() },
                ) {
                    GroupIntegrityContent(
                        dependencies = dependencies,
                        flow = flow,
                        onOpenConsent = onOpenConsent,
                    )
                }
            }
        }
    }
}

// ── Hub root ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubScreen(
    onOpenSeat: (String) -> Unit,
    onUseRecommended: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(OnboardingR.string.onboarding_services_title)) },
                actions = {
                    TextButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("onboarding.services.hub.done"),
                    ) { Text(stringResource(OnboardingR.string.onboarding_hub_done)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsFootnote(stringResource(OnboardingR.string.onboarding_hub_intro))

            SettingsSectionLabel(
                stringResource(OnboardingR.string.onboarding_hub_section_needed),
            )
            SettingsCard {
                HubRow(
                    icon = Icons.Filled.SettingsInputAntenna,
                    tile = SettingsTile.Purple,
                    titleRes = OnboardingR.string.onboarding_hub_row_message_title,
                    subtitleRes = OnboardingR.string.onboarding_hub_row_message_subtitle,
                    testTag = "onboarding.services.hub.messageDelivery",
                    onClick = { onOpenSeat(ROUTE_MESSAGE) },
                )
                HubRow(
                    icon = Icons.Filled.PhotoLibrary,
                    tile = SettingsTile.Red,
                    titleRes = OnboardingR.string.onboarding_hub_row_media_title,
                    subtitleRes = OnboardingR.string.onboarding_hub_row_media_subtitle,
                    testTag = "onboarding.services.hub.mediaDelivery",
                    onClick = { onOpenSeat(ROUTE_MEDIA) },
                )
                HubRow(
                    icon = Icons.Filled.Search,
                    tile = SettingsTile.Blue,
                    titleRes = OnboardingR.string.onboarding_hub_row_directory_title,
                    subtitleRes = OnboardingR.string.onboarding_hub_row_directory_subtitle,
                    testTag = "onboarding.services.hub.directory",
                    onClick = { onOpenSeat(ROUTE_DIRECTORY) },
                    isLast = true,
                )
            }

            SettingsSectionLabel(
                stringResource(OnboardingR.string.onboarding_hub_section_optional),
            )
            SettingsCard {
                HubRow(
                    icon = Icons.Filled.Verified,
                    tile = SettingsTile.Green,
                    titleRes = OnboardingR.string.onboarding_hub_row_group_title,
                    subtitleRes = OnboardingR.string.onboarding_hub_row_group_subtitle,
                    testTag = "onboarding.services.hub.groupIntegrity",
                    onClick = { onOpenSeat(ROUTE_GROUP) },
                    isLast = true,
                )
            }

            SettingsFootnote(stringResource(OnboardingR.string.onboarding_hub_footnote))

            TextButton(
                onClick = onUseRecommended,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp)
                    .testTag("onboarding.services.hub.use_recommended"),
            ) { Text(stringResource(OnboardingR.string.onboarding_hub_use_recommended)) }

            // Switching back does NOT undo anything — say so, or the
            // affordance reads as a revert.
            Text(
                text = stringResource(
                    OnboardingR.string.onboarding_hub_use_recommended_footnote,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HubRow(
    icon: ImageVector,
    tile: androidx.compose.ui.graphics.Color,
    titleRes: Int,
    subtitleRes: Int,
    testTag: String,
    onClick: () -> Unit,
    isLast: Boolean = false,
) {
    SettingsRow(
        leading = { SettingsTileBox(icon, tile) },
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleRes),
        onClick = onClick,
        isLast = isLast,
        modifier = Modifier.testTag(testTag),
    )
}

// ── Seat screen frame ─────────────────────────────────────────────

/** One pushed hub screen: top bar with an explicit back affordance
 *  (system back pops the same NavHost entry), the seat's surface in
 *  a scroll column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeatScreen(
    titleRes: Int,
    backTag: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(backTag),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                OnboardingR.string.onboarding_back,
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Shared seat pieces ────────────────────────────────────────────

/** One configured-endpoint row: check tile + name + URL (mono),
 *  optional role chip. The chip policy is per-seat and honest — see
 *  each caller. */
@Composable
private fun SeatEndpointRow(
    name: String,
    url: String,
    chip: String?,
    chipColor: androidx.compose.ui.graphics.Color,
    isLast: Boolean,
    tag: String,
) {
    SettingsRow(
        leading = {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        },
        title = name,
        subtitle = url,
        subtitleMono = true,
        trailing = { if (chip != null) DiscoveryChip(chip, chipColor) },
        isLast = isLast,
        insetHairline = 16.dp,
        modifier = Modifier.testTag(tag),
    )
}

/** Custom-URL entry: field + Add button, driven by the seat VM's
 *  draft intents — same behavior as the Settings screens. */
@Composable
private fun SeatCustomUrlField(
    placeholder: String,
    draft: String,
    error: String?,
    tagPrefix: String,
    onDraftChanged: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            placeholder = { Text(placeholder) },
            singleLine = true,
            isError = error != null,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("$tagPrefix.custom_url_field"),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("$tagPrefix.custom_error"),
            )
        }
        Button(
            onClick = onAdd,
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("$tagPrefix.add_custom"),
        ) { Text(stringResource(OnboardingR.string.onboarding_seat_add)) }
    }
}

/** The catalog section, shared by the seat screens. The screens draw
 *  their own "SUGGESTED BY YOUR DIRECTORIES" header; the card itself
 *  renders no header, so nothing doubles a "FROM CATALOG" label. */
@Composable
private fun SeatCatalogSection(
    dependencies: AppDependencies,
    seatType: String,
    tagPrefix: String,
    onOpenConsent: (AttributedCatalogEntry) -> Unit,
) {
    val (entries, consents) = rememberSeatCatalog(
        dependencies = dependencies,
        seatType = seatType,
    )
    if (entries.isNotEmpty()) {
        SettingsSectionLabel(
            stringResource(OnboardingR.string.onboarding_seat_section_suggested),
        )
        DiscoveryCatalogCard(
            entries = entries,
            consentByComponentId = consents,
            onEntryClick = onOpenConsent,
            testTagPrefix = tagPrefix,
        )
    }
}

/** Plain informational card for seats with nothing configured. */
@Composable
private fun SeatInfoCard(text: String, tag: String) {
    SettingsCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(16.dp)
                .testTag(tag),
        )
    }
}

/** Records the seat's custom-URL add as a services consent — the
 *  user's own typed endpoint is their own choice, not a catalog
 *  module, so there is no componentId and no consent flow. */
private fun recordCustomAdd(flow: OnboardingFlow) {
    if (flow.state.value.step == app.onym.android.onboarding.OnboardingStep.Services) {
        flow.recordOutcome(StepOutcome.Consented(null))
    }
}

// ── Message delivery (Nostr relays) ───────────────────────────────

/**
 * TRANSPORT HONESTY: no per-row role chips. Nostr has no failover
 * order — the transport fans every event out to ALL connected relays
 * concurrently, so a PRIMARY/BACKUP chip would be a false privacy
 * claim about which relays see traffic. The explainer and list
 * footnote say exactly that.
 */
@Composable
private fun MessageDeliveryContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
    onOpenConsent: (AttributedCatalogEntry) -> Unit,
) {
    val viewModel: NostrRelaySettingsViewModel = viewModel(
        key = "onboarding.nostrSettings",
        factory = viewModelFactory {
            initializer { dependencies.makeNostrRelaySettingsViewModel() }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        SettingsFootnote(stringResource(OnboardingR.string.onboarding_message_explainer))

        val endpoints = state.snapshot.endpoints
        if (endpoints.isEmpty()) {
            SeatInfoCard(
                text = stringResource(OnboardingR.string.onboarding_message_empty),
                tag = "onboarding.services.messageDelivery.empty",
            )
        } else {
            SettingsSectionLabel(
                stringResource(OnboardingR.string.onboarding_seat_section_in_use),
            )
            SettingsCard {
                endpoints.forEachIndexed { idx, endpoint ->
                    SeatEndpointRow(
                        name = endpoint.name,
                        url = endpoint.url,
                        // No role chip — see the content KDoc.
                        chip = null,
                        chipColor = SettingsTile.Green,
                        isLast = idx == endpoints.lastIndex,
                        tag = "onboarding.services.messageDelivery.configured.${endpoint.url}",
                    )
                }
            }
            SettingsFootnote(
                stringResource(OnboardingR.string.onboarding_message_list_footnote),
            )
        }

        SeatCatalogSection(
            dependencies = dependencies,
            seatType = DiscoveryBackedKnownNostrRelaysFetcher.SEAT_TYPE,
            tagPrefix = "onboarding.services.messageDelivery.catalog",
            onOpenConsent = onOpenConsent,
        )

        SettingsSectionLabel(
            stringResource(OnboardingR.string.onboarding_seat_section_add_own),
        )
        SeatCustomUrlField(
            placeholder = "wss://relay.example.com",
            draft = state.customDraft,
            error = state.customDraftError,
            tagPrefix = "onboarding.services.messageDelivery",
            onDraftChanged = viewModel::customDraftChanged,
            onAdd = {
                val valid =
                    NostrRelaySettingsViewModel.validate(state.customDraft) != null
                viewModel.tappedAddCustom()
                if (valid) recordCustomAdd(flow)
            },
        )
    }
}

// ── Media delivery (Blossom) ──────────────────────────────────────

/**
 * TRANSPORT HONESTY: the ACTIVE chip sits on the FIRST endpoint only
 * — uploads target the first configured server, and a consented
 * catalog pick becomes first via the composition root's
 * `addEndpoint(makeActive = true)` apply closure. The rest of the
 * list is an allowlist of hosts trusted for downloads, not upload
 * fallbacks — so no chip on them, and the footnote says what they
 * actually are.
 */
@Composable
private fun MediaDeliveryContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
    onOpenConsent: (AttributedCatalogEntry) -> Unit,
) {
    val viewModel: BlossomServerSettingsViewModel = viewModel(
        key = "onboarding.blossomSettings",
        factory = viewModelFactory {
            initializer { dependencies.makeBlossomServerSettingsViewModel() }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        SettingsFootnote(stringResource(OnboardingR.string.onboarding_media_explainer))

        val endpoints = state.snapshot.endpoints
        if (endpoints.isEmpty()) {
            SeatInfoCard(
                text = stringResource(OnboardingR.string.onboarding_media_empty),
                tag = "onboarding.services.mediaDelivery.empty",
            )
        } else {
            SettingsSectionLabel(
                stringResource(OnboardingR.string.onboarding_seat_section_in_use),
            )
            SettingsCard {
                endpoints.forEachIndexed { idx, endpoint ->
                    SeatEndpointRow(
                        name = endpoint.name,
                        url = endpoint.url,
                        chip = if (idx == 0) {
                            stringResource(OnboardingR.string.onboarding_media_active_chip)
                        } else {
                            null
                        },
                        chipColor = SettingsTile.Green,
                        isLast = idx == endpoints.lastIndex,
                        tag = "onboarding.services.mediaDelivery.configured.${endpoint.url}",
                    )
                }
            }
            SettingsFootnote(stringResource(OnboardingR.string.onboarding_media_footnote))
        }

        SeatCatalogSection(
            dependencies = dependencies,
            seatType = DiscoveryBackedKnownBlossomServersFetcher.SEAT_TYPE,
            tagPrefix = "onboarding.services.mediaDelivery.catalog",
            onOpenConsent = onOpenConsent,
        )

        SettingsSectionLabel(
            stringResource(OnboardingR.string.onboarding_seat_section_add_own),
        )
        SeatCustomUrlField(
            placeholder = "https://blossom.example.com",
            draft = state.customDraft,
            error = state.customDraftError,
            tagPrefix = "onboarding.services.mediaDelivery",
            onDraftChanged = viewModel::customDraftChanged,
            onAdd = {
                val valid =
                    BlossomServerSettingsViewModel.validate(state.customDraft) != null
                viewModel.tappedAddCustom()
                if (valid) recordCustomAdd(flow)
            },
        )
    }
}

// ── Group integrity (notary / relayer) ────────────────────────────

/**
 * TRANSPORT HONESTY: the chip mirrors the ACTUAL
 * [RelayerConfiguration.selectUrl] resolution. Under
 * [RelayerStrategy.PRIMARY] exactly the endpoint selectUrl would
 * resolve is marked (the matching primaryUrl, or the first endpoint
 * when the marker is unset/stale — selectUrl's documented fallback).
 * Under [RelayerStrategy.RANDOM] (the default) no row is special —
 * each new group picks one of the list at random — so no chips, and
 * the footnote says so.
 *
 * While onboarding is incomplete the relayer repository's
 * first-launch auto-populate is suppressed, so the configured list
 * starts empty: the published list and the catalog are the offers,
 * and leaving the seat alone installs the published defaults right
 * after completion.
 */
@Composable
private fun GroupIntegrityContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
    onOpenConsent: (AttributedCatalogEntry) -> Unit,
) {
    val viewModel: RelayerSettingsViewModel = viewModel(
        key = "onboarding.relayerSettings",
        factory = viewModelFactory {
            initializer { dependencies.makeRelayerSettingsViewModel() }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configuration = state.configuration

    Column(Modifier.fillMaxWidth()) {
        SettingsFootnote(stringResource(OnboardingR.string.onboarding_notary_explainer))

        val endpoints = configuration.endpoints
        if (endpoints.isEmpty()) {
            SeatInfoCard(
                text = stringResource(OnboardingR.string.onboarding_notary_empty),
                tag = "onboarding.services.groupIntegrity.empty",
            )
        } else {
            SettingsSectionLabel(
                stringResource(OnboardingR.string.onboarding_seat_section_in_use),
            )
            SettingsCard {
                endpoints.forEachIndexed { idx, endpoint ->
                    SeatEndpointRow(
                        name = endpoint.name,
                        url = endpoint.url,
                        chip = if (isResolvedPrimary(configuration, endpoint.url, idx)) {
                            stringResource(
                                OnboardingR.string.onboarding_notary_primary_chip,
                            )
                        } else {
                            null
                        },
                        chipColor = SettingsTile.Green,
                        isLast = idx == endpoints.lastIndex,
                        tag = "onboarding.services.groupIntegrity.configured.${endpoint.url}",
                    )
                }
            }
            SettingsFootnote(
                stringResource(
                    if (configuration.strategy == RelayerStrategy.PRIMARY) {
                        OnboardingR.string.onboarding_notary_footnote_primary
                    } else {
                        OnboardingR.string.onboarding_notary_footnote_random
                    },
                ),
            )
        }

        SettingsSectionLabel(
            stringResource(OnboardingR.string.onboarding_notary_section_published),
        )
        PublishedNotaryList(
            state = state,
            onAdd = { endpoint ->
                viewModel.addKnown(endpoint)
                if (flow.state.value.step ==
                    app.onym.android.onboarding.OnboardingStep.Services
                ) {
                    flow.recordOutcome(StepOutcome.Consented(null))
                }
            },
        )

        SeatCatalogSection(
            dependencies = dependencies,
            seatType = DiscoveryBackedKnownRelayersFetcher.SEAT_TYPE,
            tagPrefix = "onboarding.services.groupIntegrity.catalog",
            onOpenConsent = onOpenConsent,
        )

        SettingsSectionLabel(
            stringResource(OnboardingR.string.onboarding_seat_section_add_own),
        )
        SeatCustomUrlField(
            placeholder = "https://relayer.example.com",
            draft = state.customDraft,
            error = state.customDraftError,
            tagPrefix = "onboarding.services.groupIntegrity",
            onDraftChanged = viewModel::customDraftChanged,
            onAdd = {
                val valid = RelayerSettingsViewModel.validate(state.customDraft) is
                    RelayerSettingsViewModel.ValidationResult.Valid
                viewModel.tappedAddCustom()
                if (valid) recordCustomAdd(flow)
            },
        )

        SettingsFootnote(stringResource(OnboardingR.string.onboarding_notary_leave_empty))
    }
}

/**
 * Whether this row is the endpoint [RelayerConfiguration.selectUrl]
 * would actually resolve under the PRIMARY strategy: the matching
 * primaryUrl (when still in the list), or the first endpoint when no
 * primary is pinned. Never true under RANDOM.
 */
private fun isResolvedPrimary(
    configuration: RelayerConfiguration,
    url: String,
    index: Int,
): Boolean {
    if (configuration.strategy != RelayerStrategy.PRIMARY) return false
    val primary = configuration.primaryUrl
    if (primary != null && configuration.endpoints.any { it.url == primary }) {
        return url == primary
    }
    return index == 0
}

/** The legacy published list — tap to add, no consent sheet: these
 *  entries are published by the onym-relayer project, not consented
 *  catalog modules. */
@Composable
private fun PublishedNotaryList(
    state: RelayerSettingsViewModel.State,
    onAdd: (app.onym.android.chain.RelayerEndpoint) -> Unit,
) {
    val unconfigured = state.unconfiguredKnownList
    SettingsCard {
        when (val status = state.fetchStatus) {
            RelayerFetchStatus.Idle, RelayerFetchStatus.Fetching -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .testTag("onboarding.services.groupIntegrity.published.fetching"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(OnboardingR.string.onboarding_notary_fetching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is RelayerFetchStatus.Failed -> Text(
                text = status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(14.dp)
                    .testTag("onboarding.services.groupIntegrity.published.failed"),
            )
            RelayerFetchStatus.Success -> if (unconfigured.isEmpty()) {
                Text(
                    text = stringResource(OnboardingR.string.onboarding_notary_all_added),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(14.dp)
                        .testTag("onboarding.services.groupIntegrity.published.all_added"),
                )
            } else {
                unconfigured.forEachIndexed { idx, endpoint ->
                    SettingsRow(
                        title = endpoint.name,
                        subtitle = endpoint.url,
                        subtitleMono = true,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                endpoint.networks.forEach { net ->
                                    DiscoveryChip(
                                        net.uppercase(),
                                        if (net == "public") {
                                            SettingsTile.Red
                                        } else {
                                            SettingsTile.Green
                                        },
                                    )
                                }
                            }
                            TextButton(
                                onClick = { onAdd(endpoint) },
                                modifier = Modifier.testTag(
                                    "onboarding.services.groupIntegrity.published.add.${endpoint.url}",
                                ),
                            ) {
                                Text(
                                    stringResource(OnboardingR.string.onboarding_seat_add),
                                )
                            }
                        },
                        isLast = idx == unconfigured.lastIndex,
                        insetHairline = 16.dp,
                        modifier = Modifier.testTag(
                            "onboarding.services.groupIntegrity.published.${endpoint.url}",
                        ),
                    )
                }
            }
        }
    }
}

// ── Directory (discovery) ─────────────────────────────────────────

/**
 * The directory seat: TOFU-confirm the seeded default discovery
 * source — the same fetch → fingerprint hero → pin-on-explicit-
 * confirm path the Settings add flow drives, through the same
 * [DiscoverySettingsViewModel] — plus "add your own provider URL"
 * feeding the same add path. Pinning the key IS the consent at this
 * seat (there's no componentId; the pin is to a provider source, not
 * a catalog component).
 */
@Composable
private fun DirectoryContent(
    dependencies: AppDependencies,
    flow: OnboardingFlow,
) {
    val discovery = dependencies.discovery
    if (discovery == null) {
        SettingsFootnote(stringResource(OnboardingR.string.onboarding_directory_explainer))
        SeatInfoCard(
            text = stringResource(OnboardingR.string.onboarding_directory_unavailable),
            tag = "onboarding.services.directory.unavailable",
        )
        return
    }

    // Store-scoped (same pattern as RootScreen's destinations), NOT
    // remember{}: a remembered androidx ViewModel never gets
    // onCleared(), so its Eagerly-started snapshots collector would
    // leak once per back→forward pass through this screen.
    val viewModel: DiscoverySettingsViewModel = viewModel(
        key = "onboarding.discoverySettings",
        factory = viewModelFactory {
            initializer { discovery.makeDiscoverySettingsViewModel() }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddOwn by remember { mutableStateOf(false) }

    val pinnedSource = state.discovery.sources.firstOrNull { it.pinnedOperatorKeyHex != null }
    // Pinning is the consent — record it (once pinned it stays
    // recorded; the guard keeps the write on the services step).
    LaunchedEffect(pinnedSource?.providerId) {
        if (pinnedSource != null &&
            flow.state.value.step == app.onym.android.onboarding.OnboardingStep.Services
        ) {
            flow.recordOutcome(StepOutcome.Consented(null))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        when (val phase = state.addPhase) {
            is DiscoverySettingsViewModel.AddPhase.Fetching -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .size(22.dp)
                            .testTag("onboarding.services.directory.fetching"),
                    )
                    Text(
                        text = stringResource(
                            OnboardingR.string.onboarding_directory_fetching,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is DiscoverySettingsViewModel.AddPhase.Confirming -> {
                DirectoryConfirm(viewModel = viewModel, pending = phase.pending)
            }

            is DiscoverySettingsViewModel.AddPhase.Added -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .testTag("onboarding.services.directory.added"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SettingsTile.Green,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(
                            OnboardingR.string.onboarding_directory_added_title,
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        text = stringResource(
                            OnboardingR.string.onboarding_directory_added_subtitle,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            else -> {
                SettingsFootnote(
                    stringResource(OnboardingR.string.onboarding_directory_explainer),
                )

                val source = state.discovery.sources.firstOrNull()
                if (source != null) {
                    SettingsSectionLabel(
                        stringResource(OnboardingR.string.onboarding_seat_section_in_use),
                    )
                    SettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .testTag("onboarding.services.directory.source"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = source.label,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            Text(
                                text = source.url,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            source.operatorKeyFingerprint?.let { fingerprint ->
                                Text(
                                    text = stringResource(
                                        OnboardingR.string.onboarding_directory_key,
                                        fingerprint,
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (source.pinnedOperatorKeyHex == null) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.addDraftChanged(source.url)
                                viewModel.tappedFetchProvider()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .testTag("onboarding.services.directory.confirm"),
                        ) {
                            Text(
                                stringResource(
                                    OnboardingR.string.onboarding_directory_confirm,
                                ),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .testTag("onboarding.services.directory.pinned"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Verified,
                                contentDescription = null,
                                tint = SettingsTile.Green,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(
                                    OnboardingR.string.onboarding_directory_pinned,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = SettingsTile.Green,
                            )
                        }
                    }
                } else {
                    SeatInfoCard(
                        text = stringResource(
                            OnboardingR.string.onboarding_directory_empty,
                        ),
                        tag = "onboarding.services.directory.empty",
                    )
                }

                state.addError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("onboarding.services.directory.error"),
                    )
                }

                if (showAddOwn) {
                    SettingsSectionLabel(
                        stringResource(OnboardingR.string.onboarding_seat_section_add_own),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.addDraft,
                            onValueChange = viewModel::addDraftChanged,
                            placeholder = {
                                Text("https://discovery.example.com/manifest.json")
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding.services.directory.add_url_field"),
                        )
                        Button(
                            onClick = viewModel::tappedFetchProvider,
                            enabled = state.addDraft.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding.services.directory.fetch"),
                        ) {
                            Text(
                                stringResource(
                                    OnboardingR.string.onboarding_directory_fetch,
                                ),
                            )
                        }
                    }
                } else {
                    TextButton(
                        onClick = { showAddOwn = true },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 8.dp)
                            .testTag("onboarding.services.directory.add_own"),
                    ) {
                        Text(
                            stringResource(OnboardingR.string.onboarding_directory_add_own),
                        )
                    }
                }
            }
        }
    }
}

/** The TOFU confirmation: fingerprint hero + provider summary.
 *  Nothing is pinned until the explicit confirm. */
@Composable
private fun DirectoryConfirm(
    viewModel: DiscoverySettingsViewModel,
    pending: app.onym.android.discovery.PendingDiscoverySource,
) {
    Column(Modifier.fillMaxWidth()) {
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
                    .testTag("onboarding.services.directory.fingerprint"),
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
            SettingsRow(
                title = stringResource(R.string.discovery_field_manifest_url),
                subtitle = pending.url,
                subtitleMono = true,
                isLast = true,
                insetHairline = 16.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = viewModel::tappedConfirmAdd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("onboarding.services.directory.pin"),
        ) { Text(stringResource(R.string.discovery_confirm_pin)) }
        TextButton(
            onClick = viewModel::tappedCancelPreview,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag("onboarding.services.directory.cancel_preview"),
        ) { Text(stringResource(R.string.back)) }
    }
}
