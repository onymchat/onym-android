package app.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import app.onym.android.R
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.chain.RelayerFetchStatus
import app.onym.android.chain.RelayerStrategy

/**
 * One-screen relayer configuration UI: Strategy / Configured / Add.
 *
 * Renamed from `RelayerPickerScreen` (PR #17) when the picker grew
 * from "pick one" to "configure many + strategy".
 *
 * Strings inline (English-only per the iOS twin / brief).
 *
 * Mirrors `RelayerSettingsView` from onym-ios PR #20.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayerSettingsScreen(
    viewModel: RelayerSettingsViewModel,
    onBackClick: () -> Unit,
    onRunYourOwnClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.relayer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        LazyColumn(contentPadding = padding) {

            // ─── Strategy ────────────────────────────────────────
            item { SectionHeader(stringResource(R.string.relayer_strategy_label)) }
            item {
                StrategySelector(
                    strategy = state.configuration.strategy,
                    onChange = viewModel::setStrategy,
                )
            }
            item {
                Footer(
                    stringResource(
                        when (state.configuration.strategy) {
                            RelayerStrategy.PRIMARY -> R.string.relayer_strategy_footer_primary
                            RelayerStrategy.RANDOM -> R.string.relayer_strategy_footer_random
                        }
                    )
                )
            }

            // ─── Configured ──────────────────────────────────────
            item { SectionHeader(stringResource(R.string.relayer_section_configured)) }
            if (state.configuration.endpoints.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.relayer_empty_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(state.configuration.endpoints, key = { it.url }) { endpoint ->
                    // `key` keeps each row's swipe-state independent
                    // so a re-emission after delete doesn't leave the
                    // next row visually dismissed (Compose reuses
                    // SwipeToDismissBox state by position otherwise).
                    key(endpoint.url) {
                        SwipeableEndpointRow(
                            endpoint = endpoint,
                            isPrimary = endpoint.url == state.configuration.primaryUrl,
                            onTogglePrimary = { viewModel.setPrimary(endpoint.url) },
                            onDelete = { viewModel.removeEndpoint(endpoint.url) },
                        )
                    }
                }
            }

            // ─── Add from Published List ─────────────────────────
            // Gate on fetchStatus, not just `unconfiguredKnownList.isEmpty()`.
            // PR #20 spun the spinner forever after a failed fetch
            // because the only "empty" branch was the spinner. The
            // four-way gate below surfaces the failure with a Try
            // Again button (Failed), the genuinely-empty state with a
            // dedicated string (Success + empty published list), and
            // keeps the spinner only for the truly-fetching cold path.
            // Mirrors `knownSectionContent` from onym-ios PR #23.
            item { SectionHeader(stringResource(R.string.relayer_section_add_known)) }
            when (val status = state.fetchStatus) {
                RelayerFetchStatus.Idle, RelayerFetchStatus.Fetching -> {
                    if (state.knownList.isEmpty()) {
                        item { FetchingSpinnerWithLabel() }
                    } else {
                        knownAddItems(state.unconfiguredKnownList) { viewModel.addKnown(it) }
                    }
                }
                RelayerFetchStatus.Success -> {
                    when {
                        state.knownList.isEmpty() -> item {
                            Text(
                                stringResource(R.string.relayer_fetch_no_published_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            )
                        }
                        state.unconfiguredKnownList.isEmpty() -> item {
                            Text(
                                stringResource(R.string.relayer_known_all_added),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            )
                        }
                        else -> knownAddItems(state.unconfiguredKnownList) { viewModel.addKnown(it) }
                    }
                }
                is RelayerFetchStatus.Failed -> {
                    item {
                        FetchFailedRow(
                            message = status.message,
                            onRetry = viewModel::tappedRetryFetch,
                        )
                    }
                }
            }

            // ─── Add Custom URL ──────────────────────────────────
            item { SectionHeader(stringResource(R.string.relayer_section_add_custom)) }
            item {
                CustomAddRow(
                    draft = state.customDraft,
                    error = state.customDraftError,
                    onDraftChange = viewModel::customDraftChanged,
                    onAdd = viewModel::tappedAddCustom,
                )
            }
            item { Footer(stringResource(R.string.relayer_custom_footer)) }

            // ─── Run your own relayer (dark CTA) ─────────────────
            item { RunYourOwnRelayerCta(onClick = onRunYourOwnClick) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun RunYourOwnRelayerCta(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1B1F24), Color(0xFF0D1117)),
                )
            )
            .clickable(onClick = onClick)
            .padding(18.dp)
            .testTag("relayer.run_your_own"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Code,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.relayer_run_your_own_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.relayer_run_your_own_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Spinner + label, used while the boot fetch is in flight. */
@Composable
private fun FetchingSpinnerWithLabel() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.testTag("relayer.add.known.fetching"),
            strokeWidth = 2.dp,
        )
        Text(
            stringResource(R.string.relayer_known_fetching),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Failure copy + Try Again button. The retry intent re-fires
 *  `RelayerRepository.refresh()`, which republishes a fresh
 *  Fetching → Success/Failed cycle. */
@Composable
private fun FetchFailedRow(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(16.dp)
            .testTag("relayer.add.known.failed"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("relayer.add.known.retry"),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Text(stringResource(R.string.relayer_fetch_retry), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** LazyListScope helper — emits one [KnownRelayerAddRow] item per
 *  endpoint with the standard test-tag key. Extracted so the
 *  fetch-status `when` doesn't have three call sites for the same
 *  block. */
private fun androidx.compose.foundation.lazy.LazyListScope.knownAddItems(
    endpoints: List<RelayerEndpoint>,
    onClick: (RelayerEndpoint) -> Unit,
) {
    items(endpoints, key = { "known:${it.url}" }) { endpoint ->
        KnownRelayerAddRow(endpoint = endpoint, onClick = { onClick(endpoint) })
    }
}

// ─── pieces ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategySelector(
    strategy: RelayerStrategy,
    onChange: (RelayerStrategy) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(TAG_STRATEGY_PICKER),
    ) {
        val options = listOf(RelayerStrategy.PRIMARY, RelayerStrategy.RANDOM)
        options.forEachIndexed { index, opt ->
            SegmentedButton(
                selected = opt == strategy,
                onClick = { onChange(opt) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag("relayer.strategy.${opt.name.lowercase()}"),
            ) {
                Text(stringResource(opt.displayNameResId))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEndpointRow(
    endpoint: RelayerEndpoint,
    isPrimary: Boolean,
    onTogglePrimary: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // Only accept the end-to-start swipe (right-to-left) as
            // a delete intent; ignore start-to-end swipes.
            value == SwipeToDismissBoxValue.EndToStart
        },
    )

    // Once the row is fully dismissed, fire the delete intent. Done
    // in a LaunchedEffect so the animation completes before the row
    // disappears from recomposition (avoids the flash described in
    // the prompt's gotcha).
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { DeleteBackground() },
        enableDismissFromEndToStart = true,
        enableDismissFromStartToEnd = false,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("relayer.configured.${endpoint.url}"),
    ) {
        EndpointRow(endpoint = endpoint, isPrimary = isPrimary, onTogglePrimary = onTogglePrimary)
    }
}

@Composable
private fun DeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.relayer_row_delete),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun EndpointRow(
    endpoint: RelayerEndpoint,
    isPrimary: Boolean,
    onTogglePrimary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onTogglePrimary,
            modifier = Modifier.testTag("relayer.configured.${endpoint.url}.primary_button"),
        ) {
            Icon(
                imageVector = if (isPrimary) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = stringResource(
                    if (isPrimary) R.string.relayer_row_primary_label
                    else R.string.relayer_row_mark_primary
                ),
                tint = if (isPrimary) Color(0xFFFF9500)
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                endpoint.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                endpoint.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NetworkBadges(networks = endpoint.networks)
    }
}

/** One chip per network the endpoint serves. PR #23 changed
 *  endpoints from singular `network: String` to plural
 *  `networks: List<String>` because a single deployment can serve
 *  testnet + public. Custom endpoints carry the [CUSTOM_NETWORK]
 *  sentinel as their only network. */
@Composable
private fun NetworkBadges(networks: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (network in networks) {
            NetworkBadge(network = network)
        }
    }
}

@Composable
private fun NetworkBadge(network: String) {
    val (bg, fg) = when (network) {
        "testnet" -> Color(0xFF34C759).copy(alpha = 0.15f) to Color(0xFF1B7B32)
        "public" -> Color(0xFFE53935).copy(alpha = 0.15f) to Color(0xFFB71C1C)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            network,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = fg,
        )
    }
}

@Composable
private fun KnownRelayerAddRow(endpoint: RelayerEndpoint, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("relayer.add.known.${endpoint.url}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.relayer_custom_add),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                endpoint.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                endpoint.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NetworkBadges(networks = endpoint.networks)
    }
}

@Composable
private fun CustomAddRow(
    draft: String,
    error: String?,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("https://relayer.example.com") },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_CUSTOM_FIELD),
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onAdd,
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_CUSTOM_BUTTON),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Text(stringResource(R.string.relayer_custom_add), fontWeight = FontWeight.SemiBold)
        }
    }
}

// Test tags exposed for instrumented UI tests under
// app/src/androidTest/.../uitests. Stable across releases — UI test
// fakes pin against these strings.
const val TAG_STRATEGY_PICKER = "relayer.strategy.picker"
const val TAG_CUSTOM_FIELD = "relayer.add.custom.field"
const val TAG_CUSTOM_BUTTON = "relayer.add.custom.button"

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}
