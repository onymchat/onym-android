package app.onym.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.discovery.DiscoveryFetchStatus
import app.onym.android.discovery.DiscoverySource
import app.onym.android.discovery.DiscoverySourceError
import app.onym.android.strings.R

/**
 * Settings → Discovery. Lists the user's configured discovery
 * sources with per-source status (fetching / ok / failed / integrity
 * warning / unconfirmed), the pinned operator key fingerprint, and
 * how many verified catalog entries each contributes. Removing a
 * source sits behind a confirmation — removal is permanent by
 * design. New providers are added by manifest URL through the TOFU
 * screen ([AddDiscoveryProviderScreen]).
 *
 * Mirrors `DiscoverySettingsView.swift` from onym-ios
 * `discovery-settings-ui`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverySettingsScreen(
    viewModel: DiscoverySettingsViewModel,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<DiscoverySource?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.discovery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier
                .fillMaxSize()
                .testTag("discovery.list"),
        ) {
            item {
                SettingsSectionLabel(
                    stringResource(
                        R.string.discovery_section_providers,
                        state.discovery.sources.size,
                    ),
                )
            }
            if (state.discovery.sources.isEmpty()) {
                item {
                    SettingsCard {
                        Text(
                            stringResource(R.string.discovery_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(16.dp)
                                .testTag("discovery.empty"),
                        )
                    }
                }
            } else {
                item {
                    SettingsCard {
                        state.discovery.sources.forEachIndexed { idx, source ->
                            SourceRow(
                                source = source,
                                error = state.discovery.sourceErrors[source.providerId],
                                fetchStatus = state.discovery.fetchStatus,
                                entryCount = state.entryCount(source.providerId),
                                onRemove = { pendingRemoval = source },
                            )
                            if (idx != state.discovery.sources.lastIndex) {
                                SettingsHairline()
                            }
                        }
                    }
                }
            }
            item { SettingsFootnote(stringResource(R.string.discovery_providers_footnote)) }

            item { SettingsSectionLabel(stringResource(R.string.discovery_section_add)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Add, SettingsTile.Blue) },
                        title = stringResource(R.string.discovery_add_row_title),
                        subtitle = stringResource(R.string.discovery_add_row_subtitle),
                        onClick = onAddProvider,
                        isLast = true,
                        modifier = Modifier.testTag("discovery.add_row"),
                    )
                }
            }
            item { SettingsFootnote(stringResource(R.string.discovery_add_footnote)) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    pendingRemoval?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.discovery_remove_title)) },
            text = { Text(stringResource(R.string.discovery_remove_message, source.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.tappedRemove(source.providerId)
                        pendingRemoval = null
                    },
                    modifier = Modifier.testTag("discovery.remove.confirm"),
                ) {
                    Text(
                        stringResource(R.string.discovery_remove_confirm),
                        color = SettingsTile.Red,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SourceRow(
    source: DiscoverySource,
    error: DiscoverySourceError?,
    fetchStatus: DiscoveryFetchStatus,
    entryCount: Int,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .testTag("discovery.source.${source.providerId}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsTileBox(Icons.Filled.SettingsInputAntenna, SettingsTile.Purple)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    source.label,
                    style = MaterialTheme.typography.bodyLarge
                        .copy(fontWeight = FontWeight.SemiBold),
                )
                SourceStatusChip(source = source, error = error, fetchStatus = fetchStatus)
            }
            Text(
                source.url,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            val fingerprint = source.operatorKeyFingerprint
            Text(
                text = if (fingerprint != null) {
                    stringResource(R.string.discovery_key_fingerprint, fingerprint)
                } else {
                    stringResource(R.string.discovery_key_unconfirmed)
                },
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                pluralStringResource(R.plurals.discovery_catalog_entries, entryCount, entryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(
                    error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error.isIntegrity) SettingsTile.Red
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.testTag("discovery.source.${source.providerId}.remove"),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.discovery_remove_confirm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One word of status per source. Integrity findings outrank plain
 *  fetch failures — a failing signature is about the provider, not
 *  the network. */
@Composable
private fun SourceStatusChip(
    source: DiscoverySource,
    error: DiscoverySourceError?,
    fetchStatus: DiscoveryFetchStatus,
) {
    when {
        error != null && error.isIntegrity ->
            DiscoveryChip(stringResource(R.string.discovery_chip_integrity), SettingsTile.Red)
        error != null ->
            DiscoveryChip(stringResource(R.string.discovery_chip_failed), SettingsTile.Amber)
        fetchStatus is DiscoveryFetchStatus.Fetching ->
            DiscoveryChip(stringResource(R.string.discovery_chip_fetching), SettingsTile.Gray)
        source.pinnedOperatorKeyHex == null ->
            DiscoveryChip(stringResource(R.string.discovery_chip_unconfirmed), SettingsTile.Gray)
        else ->
            DiscoveryChip(stringResource(R.string.discovery_chip_ok), SettingsTile.Green)
    }
}
