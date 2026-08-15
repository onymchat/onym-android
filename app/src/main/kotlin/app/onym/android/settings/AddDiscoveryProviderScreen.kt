package app.onym.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.discovery.PendingDiscoverySource
import app.onym.android.discovery.DiscoverySource
import app.onym.android.strings.R

/**
 * Add-provider flow: type the manifest URL, fetch + verify it, then
 * confirm the operator key fingerprint (trust-on-first-use) before
 * anything is pinned. Nothing about the provider is persisted until
 * the confirm step.
 *
 * Mirrors `AddDiscoveryProviderView.swift` from onym-ios
 * `discovery-settings-ui` — a navigation destination instead of a
 * sheet, per this repo's settings conventions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDiscoveryProviderScreen(
    viewModel: DiscoverySettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.discovery_add_row_title)) },
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
                .testTag("discovery.add.screen"),
        ) {
            when (val phase = state.addPhase) {
                DiscoverySettingsViewModel.AddPhase.Idle,
                DiscoverySettingsViewModel.AddPhase.Fetching -> urlEntryItems(
                    draft = state.addDraft,
                    error = state.addError,
                    isFetching = phase is DiscoverySettingsViewModel.AddPhase.Fetching,
                    onDraftChange = viewModel::addDraftChanged,
                    onFetch = viewModel::tappedFetchProvider,
                )
                is DiscoverySettingsViewModel.AddPhase.Confirming -> confirmItems(
                    pending = phase.pending,
                    onConfirm = viewModel::tappedConfirmAdd,
                    onCancelPreview = viewModel::tappedCancelPreview,
                )
                DiscoverySettingsViewModel.AddPhase.Added -> doneItems(onDone = {
                    viewModel.resetAdd()
                    onBack()
                })
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ─── Step 1: URL ──────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.urlEntryItems(
    draft: String,
    error: String?,
    isFetching: Boolean,
    onDraftChange: (String) -> Unit,
    onFetch: () -> Unit,
) {
    item { SettingsFootnote(stringResource(R.string.discovery_add_intro)) }
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("https://discovery.example.com/manifest.json") },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyMedium
                    .copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discovery.add.field"),
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("discovery.add.error"),
                )
            }
            Button(
                onClick = onFetch,
                enabled = !isFetching && draft.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discovery.add.fetch"),
            ) {
                if (isFetching) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(18.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.discovery_add_fetch),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ─── Step 2: TOFU confirmation ────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.confirmItems(
    pending: PendingDiscoverySource,
    onConfirm: () -> Unit,
    onCancelPreview: () -> Unit,
) {
    item { SettingsFootnote(stringResource(R.string.discovery_confirm_intro)) }
    item { SettingsSectionLabel(stringResource(R.string.discovery_section_fingerprint)) }
    item {
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
                    .testTag("discovery.add.fingerprint"),
            )
        }
    }
    item { SettingsFootnote(stringResource(R.string.discovery_fingerprint_footnote)) }

    item { SettingsSectionLabel(stringResource(R.string.discovery_section_provider)) }
    item {
        SettingsCard {
            SummaryRow(stringResource(R.string.discovery_field_provider), pending.manifest.providerId)
            SettingsHairline(insetStart = 16.dp)
            SummaryRow(stringResource(R.string.discovery_field_manifest_url), pending.url)
            SettingsHairline(insetStart = 16.dp)
            SummaryRow(
                stringResource(R.string.discovery_field_valid_until),
                pending.manifest.validUntil,
            )
            SettingsHairline(insetStart = 16.dp)
            SummaryRow(
                stringResource(R.string.discovery_field_catalogs),
                pending.catalogs.joinToString("\n") { catalog ->
                    "${catalog.catalogId} · ${catalog.seatTypes.joinToString(", ")}"
                },
            )
        }
    }
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discovery.add.confirm"),
            ) {
                Text(
                    stringResource(R.string.discovery_confirm_pin),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = onCancelPreview,
                modifier = Modifier.testTag("discovery.add.back"),
            ) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Step 3: done ─────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.doneItems(onDone: () -> Unit) {
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 96.dp)
                .testTag("discovery.add.done"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = SettingsTile.Green,
                modifier = Modifier.height(44.dp),
            )
            Text(
                stringResource(R.string.discovery_added_title),
                style = MaterialTheme.typography.titleMedium
                    .copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                stringResource(R.string.discovery_added_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag("discovery.add.done_button"),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}
