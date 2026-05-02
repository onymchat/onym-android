package chat.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerStrategy

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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Relayer") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        LazyColumn(contentPadding = padding) {

            // ─── Strategy ────────────────────────────────────────
            item { SectionHeader("STRATEGY") }
            item {
                StrategySelector(
                    strategy = state.configuration.strategy,
                    onChange = viewModel::setStrategy,
                )
            }
            item {
                Footer(
                    when (state.configuration.strategy) {
                        RelayerStrategy.PRIMARY -> "Always use the primary endpoint. Falls back to the first " +
                            "configured endpoint if no primary is set or the primary was removed."
                        RelayerStrategy.RANDOM -> "Pick a random endpoint per request. Distributes load evenly."
                    }
                )
            }

            // ─── Configured ──────────────────────────────────────
            item { SectionHeader("CONFIGURED") }
            if (state.configuration.endpoints.isEmpty()) {
                item {
                    Text(
                        "No endpoints configured. Add one below.",
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
            item { SectionHeader("ADD FROM PUBLISHED LIST") }
            if (state.unconfiguredKnownList.isEmpty()) {
                item {
                    Text(
                        if (state.configuration.endpoints.isEmpty()) "Fetching list…"
                        else "All published relayers are already configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(state.unconfiguredKnownList, key = { "known:${it.url}" }) { endpoint ->
                    KnownRelayerAddRow(
                        endpoint = endpoint,
                        onClick = { viewModel.addKnown(endpoint) },
                    )
                }
            }

            // ─── Add Custom URL ──────────────────────────────────
            item { SectionHeader("ADD CUSTOM URL") }
            item {
                CustomAddRow(
                    draft = state.customDraft,
                    error = state.customDraftError,
                    onDraftChange = viewModel::customDraftChanged,
                    onAdd = viewModel::tappedAddCustom,
                )
            }
            item {
                Footer(
                    "For private deployments, localhost development, or sideloaded networks. " +
                        "URL must use http or https."
                )
            }
        }
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val options = listOf(RelayerStrategy.PRIMARY, RelayerStrategy.RANDOM)
        options.forEachIndexed { index, opt ->
            SegmentedButton(
                selected = opt == strategy,
                onClick = { onChange(opt) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    when (opt) {
                        RelayerStrategy.PRIMARY -> "Primary"
                        RelayerStrategy.RANDOM -> "Random"
                    }
                )
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
            contentDescription = "Delete",
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
        IconButton(onClick = onTogglePrimary) {
            Icon(
                imageVector = if (isPrimary) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (isPrimary) "Primary endpoint" else "Mark as primary",
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
        NetworkBadge(network = endpoint.network)
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add",
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
        NetworkBadge(network = endpoint.network)
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
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Text("Add", fontWeight = FontWeight.SemiBold)
        }
    }
}

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
