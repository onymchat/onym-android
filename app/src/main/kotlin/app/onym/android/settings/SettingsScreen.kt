package app.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.BuildConfig
import app.onym.android.strings.R
import app.onym.android.design.OnymMark
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.identity.IdentitiesViewModel
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.inviteUrl

/**
 * Settings home — Apple-Settings-style, brand-anchored on the
 * broken-ring Onym mark.
 *
 * Layout (top → bottom):
 *
 *   1. Active-identity hero (tap → identity detail).
 *   2. SECURITY section: Identities · Privacy & Encryption.
 *   3. NETWORK section: Relays · Anchors · Use Mainnet toggle.
 *   4. APP section: About Onym.
 *   5. Brand watermark + "open · anonymous · onchain" tagline.
 *
 * The redesign drops the original Backup row + standalone Identities
 * row in favor of the Identity Detail card (per-identity backup) and
 * a sectioned home that mirrors iOS Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    identitiesViewModel: IdentitiesViewModel,
    onRelayerClick: () -> Unit,
    onAnchorsClick: () -> Unit,
    /** Launch the recovery-phrase backup flow (active identity). Wired to
     *  the carousel's per-identity Backup action. */
    onBackup: () -> Unit,
    onAboutClick: () -> Unit,
    /** App-wide network preference. Bound to the Settings → Network
     *  → "Use Mainnet" Switch. */
    useMainnet: Boolean,
    onToggleMainnet: (Boolean) -> Unit,
    /** Symmetric read-receipt setting. Bound to the Settings → Chat
     *  → "Send read receipts" Switch. */
    sendReadReceipts: Boolean = true,
    onToggleReadReceipts: (Boolean) -> Unit = {},
    /** Settings → Transport → Nostr Relays entry. PR 87. */
    onNostrRelaysClick: (() -> Unit)? = null,
    /** Live count of configured Nostr relays — drives the Settings
     *  row's subtitle. */
    nostrRelaysCount: Int = 0,
    /** Settings → Transport → Blossom Relays entry. */
    onBlossomRelaysClick: (() -> Unit)? = null,
    /** Live count of configured Blossom servers — drives the Settings
     *  row's subtitle. */
    blossomRelaysCount: Int = 0,
    /** Wipe every local message (keeps chats). Invoked only after the
     *  Data → "Clear local message cache" two-step confirmation. */
    onClearMessages: () -> Unit = {},
    /** Re-run the first-launch seat-selection walk (keeps identity,
     *  chats, messages). Invoked only after the confirmation dialog.
     *  Null hides the row (onboarding not wired). */
    onRestartOnboarding: (() -> Unit)? = null,
    /** Settings → Discovery entry. Null when discovery isn't wired
     *  (UI-test harness) — the section is omitted entirely. */
    onDiscoveryClick: (() -> Unit)? = null,
    /** Live count of configured discovery providers — drives the
     *  Discovery row's subtitle. */
    discoveryProvidersCount: Int = 0,
    /** Settings → Moderation entry. Null when the moderation seat is
     *  dark — the section is omitted entirely. */
    onModerationClick: (() -> Unit)? = null,
    /** The CURRENT IDENTITY's resolved consent state, driving the
     *  Moderation row's subtitle. Null = unresolved (or seat dark):
     *  no subtitle is shown rather than another identity's answer. */
    moderationConsent:
        app.onym.android.moderation.ModerationRepository.IdentityConsentState? = null,
    /** Cases the gate reports open against this identity. Surfaced as a
     *  banner at the top of Settings rather than only inside
     *  Settings → Moderation: a case the user never learns about is one
     *  they cannot answer, and the first they'd otherwise know is the
     *  ban at the end of it. Empty is the normal state. */
    openCases: List<app.onym.android.moderation.CaseNotice> = emptyList(),
    /** Review the open cases: the single case's appeal when there is
     *  one, otherwise the Moderation screen that lists them all. */
    onReviewCases: () -> Unit = {},
) {
    // Two gates of the "clear message cache" double-confirm.
    var showClearConfirm1 by remember { mutableStateOf(false) }
    var showClearConfirm2 by remember { mutableStateOf(false) }
    // Single confirm for the (non-destructive) onboarding restart.
    var showRestartConfirm by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings.list"),
        ) {
            // Above everything: an open case is time-bounded (the
            // response window runs whether or not the user has noticed)
            // and it never blocks the app, so the banner is the only
            // thing that tells them.
            if (openCases.isNotEmpty()) {
                item {
                    app.onym.android.moderation.ui.OpenCaseBanner(
                        notices = openCases,
                        onClick = onReviewCases,
                    )
                }
            }

            // ─── Identity carousel ─────────────────────────────────
            // One swipeable QR carousel replaces the old Active-identity
            // hero + Invite-QR hero + Identities row: swipe to switch
            // active, last page adds a new identity, per-page share /
            // backup / delete.
            item {
                IdentityCarouselCard(
                    viewModel = identitiesViewModel,
                    onBackup = onBackup,
                )
            }

            // The SECURITY section (Privacy & Encryption) was removed.
            // Recovery-phrase backup lives on each identity's carousel
            // page (its Backup action); the informational Privacy screen
            // is gone.

            // ─── TRANSPORT ─────────────────────────────────────────
            if (onNostrRelaysClick != null) {
                item { SettingsSectionLabel(stringResource(R.string.settings_section_transport)) }
                item {
                    SettingsCard {
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.Cloud, SettingsTile.Indigo)
                            },
                            title = stringResource(R.string.nostr_relays_title),
                            subtitle = stringResource(R.string.endpoints_configured_count, nostrRelaysCount),
                            onClick = onNostrRelaysClick,
                            isLast = onBlossomRelaysClick == null,
                            modifier = Modifier.testTag("settings.nostr_relays_row"),
                        )
                        if (onBlossomRelaysClick != null) {
                            SettingsRow(
                                leading = {
                                    SettingsTileBox(Icons.Filled.Image, SettingsTile.Indigo)
                                },
                                title = stringResource(R.string.blossom_relays_title),
                                subtitle = stringResource(R.string.endpoints_configured_count, blossomRelaysCount),
                                onClick = onBlossomRelaysClick,
                                isLast = true,
                                modifier = Modifier.testTag("settings.blossom_relays_row"),
                            )
                        }
                    }
                }
                item {
                    SettingsFootnote(
                        stringResource(R.string.settings_transport_footnote),
                    )
                }
            }

            // ─── DISCOVERY ─────────────────────────────────────────
            if (onDiscoveryClick != null) {
                item { SettingsSectionLabel(stringResource(R.string.settings_section_discovery)) }
                item {
                    SettingsCard {
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.TravelExplore, SettingsTile.Purple)
                            },
                            title = stringResource(R.string.discovery_providers_row_title),
                            subtitle = stringResource(
                                R.string.endpoints_configured_count,
                                discoveryProvidersCount,
                            ),
                            onClick = onDiscoveryClick,
                            isLast = true,
                            modifier = Modifier.testTag("settings.discovery_row"),
                        )
                    }
                }
                item {
                    SettingsFootnote(
                        stringResource(R.string.settings_discovery_footnote),
                    )
                }
            }

            // ─── MODERATION ────────────────────────────────────────
            if (onModerationClick != null) {
                item { SettingsSectionLabel(stringResource(R.string.settings_section_moderation)) }
                item {
                    SettingsCard {
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.VerifiedUser, SettingsTile.Indigo)
                            },
                            title = stringResource(R.string.settings_moderation_row_title),
                            subtitle = when {
                                moderationConsent == null -> null
                                moderationConsent.active != null -> stringResource(
                                    R.string.settings_moderation_row_consented,
                                    moderationConsent.active!!.authorityName,
                                )
                                else ->
                                    stringResource(R.string.settings_moderation_row_none)
                            },
                            onClick = onModerationClick,
                            isLast = true,
                            modifier = Modifier.testTag("settings.moderation_row"),
                        )
                    }
                }
            }

            // ─── ANCHORS ───────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.settings_section_anchors)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Anchor, SettingsTile.Orange)
                        },
                        title = stringResource(R.string.anchors_title),
                        subtitle = stringResource(
                            if (useMainnet) R.string.settings_anchors_subtitle_mainnet
                            else R.string.settings_anchors_subtitle_testnet
                        ),
                        onClick = onAnchorsClick,
                        modifier = Modifier.testTag("settings.anchors_row"),
                    )
                    // The network choice (was a "Use Mainnet" toggle) now
                    // lives inside the Anchors screen as the active-network
                    // selector — the Anchors subtitle above reflects it.
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Cloud, SettingsTile.Indigo)
                        },
                        title = stringResource(R.string.relayer_title),
                        subtitle = stringResource(R.string.settings_relayer_subtitle),
                        onClick = onRelayerClick,
                        isLast = true,
                        modifier = Modifier.testTag("settings.relayer_row"),
                    )
                }
            }
            item {
                SettingsFootnote(
                    stringResource(R.string.settings_anchors_footnote),
                )
            }

            // ─── DATA ──────────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.settings_section_data)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(
                                icon = Icons.Filled.DoneAll,
                                background = if (sendReadReceipts) SettingsTile.Indigo else SettingsTile.Gray,
                            )
                        },
                        title = stringResource(R.string.settings_read_receipts_title),
                        subtitle = stringResource(R.string.settings_read_receipts_subtitle),
                        showChevron = false,
                        trailing = {
                            Switch(
                                checked = sendReadReceipts,
                                onCheckedChange = onToggleReadReceipts,
                                modifier = Modifier.testTag("settings.read_receipts_toggle"),
                            )
                        },
                        onClick = { onToggleReadReceipts(!sendReadReceipts) },
                    )
                    if (onRestartOnboarding != null) {
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.RestartAlt, SettingsTile.Gray)
                            },
                            title = stringResource(R.string.settings_restart_onboarding_title),
                            subtitle = stringResource(R.string.settings_restart_onboarding_subtitle),
                            showChevron = false,
                            onClick = { showRestartConfirm = true },
                            modifier = Modifier.testTag("settings.restart_onboarding_row"),
                        )
                    }
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.DeleteSweep, SettingsTile.Red)
                        },
                        title = stringResource(R.string.settings_clear_cache_title),
                        titleColor = SettingsTile.Red,
                        subtitle = stringResource(R.string.settings_clear_cache_subtitle),
                        showChevron = false,
                        onClick = { showClearConfirm1 = true },
                        isLast = true,
                        modifier = Modifier.testTag("settings.clear_messages_row"),
                    )
                }
            }
            item {
                SettingsFootnote(
                    "Onym keeps no copy of your messages on any server — this device is " +
                        "the only place they live. Cleared messages can't be downloaded " +
                        "again: relays hold them only briefly and may already have dropped them."
                )
            }

            // ─── Brand watermark ───────────────────────────────────
            item { Spacer(Modifier.height(28.dp)) }
            item { BrandFooter() }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Restart onboarding: one confirmation, stating explicitly what is
    // KEPT (identity, chats, messages) and what re-runs (the seat
    // selections). Nothing is deleted, so no double-confirm.
    if (showRestartConfirm && onRestartOnboarding != null) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.settings_restart_dialog_title)) },
            text = { Text(stringResource(R.string.settings_restart_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartConfirm = false
                        onRestartOnboarding()
                    },
                    modifier = Modifier.testTag("settings.restart_onboarding.confirm"),
                ) {
                    Text(stringResource(R.string.settings_restart_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestartConfirm = false },
                    modifier = Modifier.testTag("settings.restart_onboarding.cancel"),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Double confirmation: the first dialog explains what's lost and that
    // it can't be re-downloaded; the second is a final are-you-sure.
    if (showClearConfirm1) {
        AlertDialog(
            onDismissRequest = { showClearConfirm1 = false },
            title = { Text(stringResource(R.string.settings_clear_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_clear_dialog_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm1 = false
                        showClearConfirm2 = true
                    },
                    modifier = Modifier.testTag("settings.clear_messages.confirm1"),
                ) {
                    Text(stringResource(R.string.settings_clear_confirm), color = SettingsTile.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm1 = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showClearConfirm2) {
        AlertDialog(
            onDismissRequest = { showClearConfirm2 = false },
            title = { Text(stringResource(R.string.settings_delete_dialog_title)) },
            text = { Text(stringResource(R.string.settings_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm2 = false
                        onClearMessages()
                    },
                    modifier = Modifier.testTag("settings.clear_messages.confirm2"),
                ) {
                    Text(stringResource(R.string.settings_delete_confirm), color = SettingsTile.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm2 = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun BrandFooter() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnymMark(
            size = 26.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
        Text(
            text = stringResource(R.string.about_credits_line1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.settings_about_subtitle, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
