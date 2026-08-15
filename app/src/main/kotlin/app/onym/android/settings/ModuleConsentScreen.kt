package app.onym.android.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.foundation.ServiceOffer
import app.onym.android.foundation.SignedServiceManifest
import app.onym.android.strings.R

/**
 * The generalized consent surface for a catalog-listed service
 * (transport / notary / blob storage): who listed it and under what
 * relationship, the operator key fingerprint, the offers (free
 * selectable, paid visible but disabled), the linked terms when the
 * operator published any, and the exact manifest hash the acceptance
 * pins. One explicit accept button.
 *
 * Mirrors `ModuleConsentView.swift` from onym-ios
 * `discovery-settings-ui`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleConsentScreen(
    viewModel: ModuleConsentViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.consent_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
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
                .testTag("module_consent.screen"),
        ) {
            when (state.step) {
                ModuleConsentViewModel.Step.Loading -> loadingItems(
                    error = state.errorMessage,
                    onRetry = viewModel::tappedRetry,
                )
                ModuleConsentViewModel.Step.Reviewing,
                ModuleConsentViewModel.Step.Applying -> reviewItems(
                    viewModel = viewModel,
                    state = state,
                )
                ModuleConsentViewModel.Step.Done -> doneItems(onDone = onClose)
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ─── Loading ──────────────────────────────────────────────────────

private fun LazyListScope.loadingItems(error: String?, onRetry: () -> Unit) {
    item {
        if (error != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("module_consent.error"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("module_consent.retry"),
                ) {
                    Text(stringResource(R.string.relayer_fetch_retry))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.consent_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Review ───────────────────────────────────────────────────────

private fun LazyListScope.reviewItems(
    viewModel: ModuleConsentViewModel,
    state: ModuleConsentViewModel.State,
) {
    val reviewed = state.reviewed ?: return
    val manifest = reviewed.signedManifest

    item {
        Text(
            viewModel.displayName,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    // Who listed this entry, and their declared relationship to it —
    // text, not color, so the disclosure survives every rendering.
    item {
        viewModel.entry?.let { entry ->
            var text = stringResource(
                R.string.discovery_listed_by,
                entry.attribution.sourceLabel,
                ModuleConsentViewModel.disclosureText(entry.attribution.relationship.wireValue),
            )
            if (entry.attribution.placement.lowercase() != "organic") {
                text += stringResource(
                    R.string.consent_placement_suffix,
                    ModuleConsentViewModel.disclosureText(entry.attribution.placement),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("module_consent.attribution"),
            )
        }
    }

    item { SettingsSectionLabel(stringResource(R.string.consent_section_service)) }
    item {
        SettingsCard {
            TermRow(stringResource(R.string.consent_field_seat), manifest.seat)
            SettingsHairline(insetStart = 16.dp)
            TermRow(stringResource(R.string.consent_field_component), manifest.componentId)
            SettingsHairline(insetStart = 16.dp)
            TermRow(
                stringResource(R.string.consent_field_operator_fingerprint),
                viewModel.operatorKeyFingerprint ?: "—",
            )
            manifest.validUntil?.let { validUntil ->
                SettingsHairline(insetStart = 16.dp)
                TermRow(stringResource(R.string.discovery_field_valid_until), validUntil.toString())
            }
        }
    }

    if (manifest.offers.isNotEmpty()) {
        offersItems(viewModel = viewModel, state = state, manifest = manifest)
    }

    viewModel.termsUrl?.let { termsUrl ->
        item { SettingsSectionLabel(stringResource(R.string.consent_section_terms)) }
        item {
            val uriHandler = LocalUriHandler.current
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(termsUrl) } }
                        .padding(16.dp)
                        .testTag("module_consent.terms"),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        stringResource(R.string.consent_terms_title),
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        termsUrl,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    item { SettingsSectionLabel(stringResource(R.string.consent_section_accepting)) }
    item {
        SettingsCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.consent_manifest_hash),
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    manifest.manifestHash,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("module_consent.hash"),
                )
            }
        }
    }
    item { SettingsFootnote(stringResource(R.string.consent_accept_footnote)) }

    state.errorMessage?.let { error ->
        item {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }

    item {
        Button(
            onClick = viewModel::tappedAccept,
            enabled = state.step != ModuleConsentViewModel.Step.Applying,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag("module_consent.accept"),
        ) {
            if (state.step == ModuleConsentViewModel.Step.Applying) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp),
                )
            } else {
                Text(
                    stringResource(R.string.consent_accept_button),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ─── Offers ───────────────────────────────────────────────────────

private fun LazyListScope.offersItems(
    viewModel: ModuleConsentViewModel,
    state: ModuleConsentViewModel.State,
    manifest: SignedServiceManifest,
) {
    item { SettingsSectionLabel(stringResource(R.string.consent_section_offers)) }
    item {
        SettingsCard {
            manifest.offers.forEachIndexed { idx, offer ->
                OfferRow(
                    offer = offer,
                    isEntitled = offer.offerId in state.entitledOfferIds,
                    isSelected = state.selectedOfferId == offer.offerId,
                    onSelect = { viewModel.selectOffer(offer.offerId) },
                )
                if (idx != manifest.offers.lastIndex) SettingsHairline(insetStart = 48.dp)
            }
        }
    }
    if (manifest.offers.any { it.offerId !in state.entitledOfferIds }) {
        item { SettingsFootnote(stringResource(R.string.consent_paid_footnote)) }
    }
}

@Composable
private fun OfferRow(
    offer: ServiceOffer,
    isEntitled: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEntitled, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("module_consent.offer.${offer.offerId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = when {
                !isEntitled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                isSelected -> SettingsTile.Green
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.height(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                offer.offerId,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (isEntitled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            offer.period?.let { period ->
                Text(
                    period,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OfferBadge(offer)
    }
}

// ─── Shared rows / done ───────────────────────────────────────────

@Composable
private fun TermRow(label: String, value: String) {
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

private fun LazyListScope.doneItems(onDone: () -> Unit) {
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 96.dp)
                .testTag("module_consent.done"),
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
                stringResource(R.string.consent_done_title),
                style = MaterialTheme.typography.titleMedium
                    .copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                stringResource(R.string.consent_done_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag("module_consent.done_button"),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}
