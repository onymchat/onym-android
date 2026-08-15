package app.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.foundation.PinnedConsentRecord
import app.onym.android.foundation.ServiceOffer
import app.onym.android.strings.R

/**
 * Shared atoms for the discovery settings surfaces: the status chip,
 * the offer badge, and the "FROM CATALOG" section the per-seat
 * pickers (relayer, nostr) embed.
 *
 * Mirrors `DiscoveryCatalogSection.swift` + `OfferBadge.swift` from
 * onym-ios `discovery-settings-ui`.
 */

/** Small colored capsule — the settings-chip idiom shared by the
 *  discovery status, consent-state, and offer badges. */
@Composable
internal fun DiscoveryChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "FREE" in green, the model name in gray for paid tiers. */
@Composable
internal fun OfferBadge(offer: ServiceOffer) {
    DiscoveryChip(
        text = if (offer.isFree) stringResource(R.string.offer_free) else offer.model.uppercase(),
        color = if (offer.isFree) SettingsTile.Green else SettingsTile.Gray,
    )
}

/**
 * Consent state relative to the exact digest the catalog pins: same
 * hash → consented; older consent for the component → the published
 * terms changed; none → not yet reviewed.
 */
@Composable
internal fun ConsentStateChip(entry: AttributedCatalogEntry, record: PinnedConsentRecord?) {
    when {
        record != null && record.manifestHash == entry.entry.manifest.digest ->
            DiscoveryChip(stringResource(R.string.discovery_chip_consented), SettingsTile.Green)
        record != null ->
            DiscoveryChip(stringResource(R.string.discovery_chip_terms_changed), SettingsTile.Amber)
        else ->
            DiscoveryChip(stringResource(R.string.discovery_chip_review), SettingsTile.Gray)
    }
}

/**
 * "FROM CATALOG" section shared by the per-seat pickers:
 * discovery-sourced entries with their source label, relationship
 * disclosure, consent state, and — once consented — the accepted
 * offer's badge. Tapping a row hands the entry back to the caller,
 * which routes it into the module-consent flow.
 *
 * Emits nothing when [entries] is empty, so pickers without
 * discovery wired (UI-test harness, discovery absent) look exactly
 * as they did before this section existed.
 */
internal fun LazyListScope.discoveryCatalogSection(
    entries: List<AttributedCatalogEntry>,
    consentByComponentId: Map<String, PinnedConsentRecord>,
    onEntryClick: (AttributedCatalogEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    item { SettingsSectionLabel(stringResource(R.string.discovery_catalog_section)) }
    item {
        DiscoveryCatalogCard(
            entries = entries,
            consentByComponentId = consentByComponentId,
            onEntryClick = onEntryClick,
        )
    }
    item { SettingsFootnote(stringResource(R.string.discovery_catalog_footnote)) }
}

/**
 * The card itself, extracted from [discoveryCatalogSection] so
 * non-lazy hosts (the onboarding step contents render inside a
 * `verticalScroll` Column) can embed the exact same rows.
 */
@Composable
internal fun DiscoveryCatalogCard(
    entries: List<AttributedCatalogEntry>,
    consentByComponentId: Map<String, PinnedConsentRecord>,
    onEntryClick: (AttributedCatalogEntry) -> Unit,
    /** Overrides the per-row test-tag prefix (rows read
     *  `<prefix>.<componentId>`) — the onboarding hub's seat screens
     *  use their own `onboarding.services.<seat>.catalog` vocabulary;
     *  null keeps the Settings default. */
    testTagPrefix: String? = null,
) {
    SettingsCard {
        entries.forEachIndexed { idx, entry ->
            val record = consentByComponentId[entry.entry.componentId]
            SettingsRow(
                leading = {
                    SettingsTileBox(Icons.Filled.TravelExplore, SettingsTile.Purple)
                },
                title = ModuleConsentViewModel.shortComponentId(entry.entry.componentId),
                subtitle = stringResource(
                    R.string.discovery_listed_by,
                    entry.attribution.sourceLabel,
                    ModuleConsentViewModel.disclosureText(
                        entry.attribution.relationship.wireValue,
                    ),
                ),
                showChevron = false,
                trailing = {
                    // §4.2 disclosed warning/review status:
                    // rendered distinctly — the field exists so a
                    // warned entry never renders indistinguishable
                    // from a clean one.
                    entry.entry.status?.let { status ->
                        DiscoveryChip(
                            text = stringResource(
                                if (status.state == "warning") {
                                    R.string.discovery_chip_status_warning
                                } else {
                                    R.string.discovery_chip_status_review
                                },
                            ),
                            color = SettingsTile.Amber,
                        )
                    }
                    consentedOffer(record)?.let { OfferBadge(it) }
                    ConsentStateChip(entry = entry, record = record)
                },
                onClick = { onEntryClick(entry) },
                isLast = idx == entries.lastIndex,
                modifier = Modifier.testTag(
                    testTagPrefix?.let { "$it.${entry.entry.componentId}" }
                        ?: "discovery.catalog.${entry.entry.seatType}.${entry.entry.componentId}",
                ),
            )
        }
    }
}

/** The offer accepted at consent time, resolved from the pinned
 *  manifest snapshot. Offers of *unconsented* entries live in a
 *  manifest that hasn't been fetched yet — those surface inside the
 *  consent flow, not here. */
private fun consentedOffer(record: PinnedConsentRecord?): ServiceOffer? {
    val offerId = record?.offerId ?: return null
    return record.consentedManifest()?.offers?.firstOrNull { it.offerId == offerId }
}
