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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
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
import app.onym.android.R
import app.onym.android.group.OnymMark
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
) {
    // Two gates of the "clear message cache" double-confirm.
    var showClearConfirm1 by remember { mutableStateOf(false) }
    var showClearConfirm2 by remember { mutableStateOf(false) }
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
                item { SettingsSectionLabel("TRANSPORT") }
                item {
                    SettingsCard {
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.Cloud, SettingsTile.Indigo)
                            },
                            title = "Nostr Relays",
                            subtitle = if (nostrRelaysCount == 1) "1 configured"
                            else "$nostrRelaysCount configured",
                            onClick = onNostrRelaysClick,
                            isLast = onBlossomRelaysClick == null,
                            modifier = Modifier.testTag("settings.nostr_relays_row"),
                        )
                        if (onBlossomRelaysClick != null) {
                            SettingsRow(
                                leading = {
                                    SettingsTileBox(Icons.Filled.Image, SettingsTile.Indigo)
                                },
                                title = "Blossom Relays",
                                subtitle = if (blossomRelaysCount == 1) "1 configured"
                                else "$blossomRelaysCount configured",
                                onClick = onBlossomRelaysClick,
                                isLast = true,
                                modifier = Modifier.testTag("settings.blossom_relays_row"),
                            )
                        }
                    }
                }
            }

            // ─── ANCHORS ───────────────────────────────────────────
            item { SettingsSectionLabel("ANCHORS") }
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

            // ─── DATA ──────────────────────────────────────────────
            item { SettingsSectionLabel("DATA") }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(
                                icon = Icons.Filled.DoneAll,
                                background = if (sendReadReceipts) SettingsTile.Indigo else SettingsTile.Gray,
                            )
                        },
                        title = "Send read receipts",
                        subtitle = "You'll only see others' read status if this is on",
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
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.DeleteSweep, SettingsTile.Red)
                        },
                        title = "Clear Local Message Cache",
                        titleColor = SettingsTile.Red,
                        subtitle = "Delete every message on this device. Your chats stay.",
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

            // ─── APP ───────────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.settings_app_section).uppercase()) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Info, SettingsTile.Teal)
                        },
                        title = stringResource(R.string.settings_about_title),
                        subtitle = stringResource(
                            R.string.settings_about_subtitle,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE.toString(),
                        ),
                        onClick = onAboutClick,
                        isLast = true,
                        modifier = Modifier.testTag("settings.about_row"),
                    )
                }
            }

            // ─── Brand watermark ───────────────────────────────────
            item { Spacer(Modifier.height(28.dp)) }
            item { BrandFooter() }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Double confirmation: the first dialog explains what's lost and that
    // it can't be re-downloaded; the second is a final are-you-sure.
    if (showClearConfirm1) {
        AlertDialog(
            onDismissRequest = { showClearConfirm1 = false },
            title = { Text("Clear all messages?") },
            text = {
                Text(
                    "This permanently deletes every message stored on this device. " +
                        "Your chats stay in the list, but the messages inside them will be gone.\n\n" +
                        "Onym keeps no copy on its servers, and messages can't be re-downloaded — " +
                        "relay copies are best-effort and may already have expired."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm1 = false
                        showClearConfirm2 = true
                    },
                    modifier = Modifier.testTag("settings.clear_messages.confirm1"),
                ) {
                    Text("Clear Messages", color = SettingsTile.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm1 = false }) { Text("Cancel") }
            },
        )
    }
    if (showClearConfirm2) {
        AlertDialog(
            onDismissRequest = { showClearConfirm2 = false },
            title = { Text("Delete all messages?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm2 = false
                        onClearMessages()
                    },
                    modifier = Modifier.testTag("settings.clear_messages.confirm2"),
                ) {
                    Text("Delete All Messages", color = SettingsTile.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm2 = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BrandFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnymMark(
            size = 26.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
        Text(
            text = stringResource(R.string.brand_tagline),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
