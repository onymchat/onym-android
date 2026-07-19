package app.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.R
import app.onym.android.chain.AppNetwork
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.GovernanceType

/**
 * Three-level drill-down for the Anchors picker. Composables
 * receive the navigate callbacks from `RootScreen` rather than a
 * `NavController` — keeps the screens unit-testable in isolation.
 *
 * Strings inline (English-only for now per the brief / iOS twin).
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorsRootScreen(
    viewModel: AnchorsPickerViewModel,
    onVersionClick: (ContractNetwork, GovernanceType) -> Unit,
    onBackClick: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeNetwork = state.activeNetwork.contractNetwork
    val activeAvailable = state.networkAvailability[activeNetwork] == true
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.anchors_title)) },
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
            // ─── Active network selector (replaces the Use Mainnet toggle)
            item { SectionHeader(stringResource(R.string.anchors_section_active_network)) }
            items(ContractNetwork.entries) { network ->
                val available = state.networkAvailability[network] == true
                ActiveNetworkRow(
                    network = network,
                    available = available,
                    isActive = network == activeNetwork,
                    onClick = if (available) {
                        { viewModel.setActiveNetwork(network.asAppNetwork()) }
                    } else null,
                )
            }
            item { Footer(stringResource(R.string.anchors_footer)) }

            // ─── Contract versions for the active network
            if (activeAvailable) {
                item {
                    SectionHeader(
                        "CONTRACT VERSIONS · " +
                            stringResource(activeNetwork.displayNameResId).uppercase()
                    )
                }
                items(viewModel.networkRows(activeNetwork), key = { it.type.wireValue }) { row ->
                    GovernanceTypeRow(
                        type = row.type,
                        resolvedRelease = row.resolvedRelease,
                        isExplicit = row.isExplicit,
                        onClick = if (row.resolvedRelease != null) {
                            { onVersionClick(activeNetwork, row.type) }
                        } else null,
                    )
                }
            }
        }
    }
}

/** Map the anchor/manifest network to the app-preference enum. */
private fun ContractNetwork.asAppNetwork(): AppNetwork = when (this) {
    ContractNetwork.Testnet -> AppNetwork.Testnet
    ContractNetwork.Public -> AppNetwork.Mainnet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorsNetworkScreen(
    viewModel: AnchorsPickerViewModel,
    network: ContractNetwork,
    onTypeClick: (GovernanceType) -> Unit,
    onBackClick: () -> Unit,
) {
    // Re-collect on each repo snapshot so the resolved-release labels
    // stay live as the user picks versions in the deeper screen.
    viewModel.state.collectAsStateWithLifecycle()
    val rows = viewModel.networkRows(network)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(network.displayNameResId)) },
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
            item { SectionHeader(stringResource(R.string.anchors_section_governance_type)) }
            items(rows, key = { it.type.wireValue }) { row ->
                GovernanceTypeRow(
                    type = row.type,
                    resolvedRelease = row.resolvedRelease,
                    isExplicit = row.isExplicit,
                    onClick = if (row.resolvedRelease != null) {
                        { onTypeClick(row.type) }
                    } else null,
                )
            }
            item { Footer(stringResource(R.string.anchors_network_footer)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorsVersionScreen(
    viewModel: AnchorsPickerViewModel,
    network: ContractNetwork,
    type: GovernanceType,
    onBackClick: () -> Unit,
    onContractDetailClick: (version: String, contractId: String?) -> Unit = { _, _ -> },
    onUseExistingClick: () -> Unit = {},
    onDeployClick: () -> Unit = {},
) {
    viewModel.state.collectAsStateWithLifecycle()
    val rows = viewModel.versionRows(network, type)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(type.displayNameResId)) },
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
        // testTag lets instrumented tests scroll into the list to
        // reach off-screen rows (Reset is at the bottom and may not
        // be in the semantic tree on a CI emulator until scrolled
        // into view).
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.testTag("anchors.version.list"),
        ) {
            item { SectionHeader(stringResource(R.string.anchors_section_releases)) }
            items(rows, key = { it.release.release }) { row ->
                VersionRow(
                    label = row.release.release,
                    contractId = row.entry.id,
                    isCurrentlySelected = row.isCurrentlySelected,
                    onClick = {
                        viewModel.pickVersion(network, type, row.release.release)
                        onBackClick()
                    },
                    onDetailClick = { onContractDetailClick(row.release.release, row.entry.id) },
                )
            }
            item {
                Footer(stringResource(R.string.anchors_releases_detail_footnote))
            }

            // ─── Custom (deploy / use existing) ──────────────────
            item { SectionHeader(stringResource(R.string.anchors_section_custom)) }
            item {
                CustomActionRow(
                    leadingTone = SettingsTile.GitHub,
                    leadingIcon = androidx.compose.material.icons.Icons.Filled.Code,
                    title = stringResource(R.string.anchors_custom_deploy_title),
                    subtitle = stringResource(R.string.anchors_custom_deploy_subtitle),
                    onClick = onDeployClick,
                    testTagId = "anchors.custom.deploy",
                )
            }
            item {
                CustomActionRow(
                    leadingTone = SettingsTile.Indigo,
                    leadingIcon = androidx.compose.material.icons.Icons.Filled.AccountTree,
                    title = stringResource(R.string.anchors_custom_use_title),
                    subtitle = stringResource(R.string.anchors_custom_use_subtitle),
                    onClick = onUseExistingClick,
                    testTagId = "anchors.custom.use",
                )
            }
            item { Footer(stringResource(R.string.anchors_custom_footer)) }

            item { SectionHeader(stringResource(R.string.anchors_section_reset)) }
            item {
                ResetRow(
                    onClick = {
                        viewModel.resetToDefault(network, type)
                        onBackClick()
                    },
                )
            }
            item { Footer(stringResource(R.string.anchors_reset_footer)) }
        }
    }
}

@Composable
private fun CustomActionRow(
    leadingTone: androidx.compose.ui.graphics.Color,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTagId: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(testTagId),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTileBox(
            icon = leadingIcon,
            background = leadingTone,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── pieces ─────────────────────────────────────────────────────

@Composable
private fun ActiveNetworkRow(
    network: ContractNetwork,
    available: Boolean,
    isActive: Boolean,
    onClick: (() -> Unit)?,
) {
    val titleColor = if (available) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("anchors.network.${network.wireValue}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(network.displayNameResId),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = titleColor)
            if (!available) {
                Text(stringResource(R.string.anchors_no_contracts_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            isActive -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            !available -> Text(
                stringResource(R.string.create_group_soon_badge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GovernanceTypeRow(
    type: GovernanceType,
    resolvedRelease: String?,
    isExplicit: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("anchors.type.${type.wireValue}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(type.displayNameResId),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
            val subtitle = when {
                resolvedRelease == null -> stringResource(R.string.anchors_no_contract_for_type)
                isExplicit -> stringResource(R.string.anchors_subtitle_selected, resolvedRelease)
                else -> stringResource(R.string.anchors_subtitle_latest, resolvedRelease)
            }
            Text(subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun VersionRow(
    label: String,
    contractId: String,
    isCurrentlySelected: Boolean,
    onClick: () -> Unit,
    onDetailClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("anchors.version.$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
            Text(contractId,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isCurrentlySelected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDetailClick)
                .testTag("anchors.version.$label.detail"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ResetRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("anchors.version.reset"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.anchors_reset_to_default),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary)
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
