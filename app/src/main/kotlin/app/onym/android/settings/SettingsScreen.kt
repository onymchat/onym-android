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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onIdentitiesClick: () -> Unit,
    onIdentityDetailClick: (IdentityId) -> Unit,
    onPrivacyClick: () -> Unit,
    onAboutClick: () -> Unit,
    /** App-wide network preference. Bound to the Settings → Network
     *  → "Use Mainnet" Switch. */
    useMainnet: Boolean,
    onToggleMainnet: (Boolean) -> Unit,
    /** Settings → Transport → Nostr Relays entry. PR 87. */
    onNostrRelaysClick: (() -> Unit)? = null,
    /** Live count of configured Nostr relays — drives the Settings
     *  row's subtitle. */
    nostrRelaysCount: Int = 0,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    val items by identitiesViewModel.items.collectAsStateWithLifecycle()
    val active = items.firstOrNull { it.isActive }
    val identityCount = items.size
    // Resolve display copy that the LazyColumn item blocks need from
    // outside the LazyListScope (item content is @Composable, but
    // LazyListScope itself is not — `stringResource` would fail there).
    val unnamedFallback = stringResource(R.string.identity_unnamed)
    val activeName = active?.summary?.name?.ifBlank { unnamedFallback }

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
            // ─── Active identity hero ──────────────────────────────
            if (active != null && activeName != null) {
                item {
                    ActiveIdentityHero(
                        name = activeName,
                        keyHex = heroHex(active.summary.blsPublicKey),
                        onClick = { onIdentityDetailClick(active.summary.id) },
                    )
                }
                // Invite QR hero — compact card directly under the
                // active-identity row. Tap opens the identity detail
                // screen, which surfaces a full-size QR + copy/share.
                item {
                    InviteQrHero(
                        summary = active.summary,
                        activeName = activeName,
                        onClick = { onIdentityDetailClick(active.summary.id) },
                    )
                }
            }

            // ─── SECURITY ──────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.security).uppercase()) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Person, SettingsTile.Purple)
                        },
                        title = stringResource(R.string.settings_identities_title),
                        subtitle = if (identityCount == 1) {
                            stringResource(R.string.settings_identities_subtitle_one)
                        } else {
                            stringResource(R.string.settings_identities_subtitle_n, identityCount)
                        },
                        onClick = onIdentitiesClick,
                        modifier = Modifier.testTag("settings.identities_row"),
                    )
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Shield, SettingsTile.Blue)
                        },
                        title = stringResource(R.string.settings_privacy_title),
                        subtitle = stringResource(R.string.settings_privacy_subtitle),
                        onClick = onPrivacyClick,
                        isLast = true,
                        modifier = Modifier.testTag("settings.privacy_row"),
                    )
                }
            }

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
                            isLast = true,
                            modifier = Modifier.testTag("settings.nostr_relays_row"),
                        )
                    }
                }
            }

            // ─── NETWORK ───────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.settings_network).uppercase()) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = {
                            SettingsTileBox(Icons.Filled.Cloud, SettingsTile.Indigo)
                        },
                        title = stringResource(R.string.relayer_title),
                        subtitle = stringResource(R.string.settings_relayer_subtitle),
                        onClick = onRelayerClick,
                        modifier = Modifier.testTag("settings.relayer_row"),
                    )
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
                    SettingsRow(
                        leading = {
                            SettingsTileBox(
                                icon = if (useMainnet) Icons.Filled.Public else Icons.Filled.Build,
                                background = if (useMainnet) SettingsTile.Green else SettingsTile.Gray,
                            )
                        },
                        title = stringResource(R.string.settings_use_mainnet),
                        subtitle = stringResource(
                            if (useMainnet) R.string.settings_use_mainnet_on_subtitle
                            else R.string.settings_use_mainnet_off_subtitle
                        ),
                        showChevron = false,
                        trailing = {
                            Switch(
                                checked = useMainnet,
                                onCheckedChange = onToggleMainnet,
                                modifier = Modifier.testTag("settings.use_mainnet_toggle"),
                            )
                        },
                        onClick = { onToggleMainnet(!useMainnet) },
                        isLast = true,
                    )
                }
            }
            item {
                SettingsFootnote(
                    stringResource(
                        if (useMainnet) R.string.settings_use_mainnet_on_footer
                        else R.string.settings_use_mainnet_off_footer,
                    )
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
}

@Composable
private fun ActiveIdentityHero(
    name: String,
    keyHex: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("settings.identity_hero"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(60.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(SettingsTile.Blue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                OnymMark(size = 36.dp, color = SettingsTile.Blue)
            }
            // Active-state dot (bottom-right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(SettingsTile.Green),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_active_identity_label).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = keyHex,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Compact "Invite key" hero — a small QR with the active identity's
 * invite URL on the left, an eyebrow + headline + subtitle on the
 * right. Tapping the card hands off to the Identity Detail screen
 * for the full-size QR + Copy / Share actions.
 *
 * Mirrors the iOS prototype's second hero card under the active-
 * identity row (`settings.jsx` lines 559–587).
 */
@Composable
private fun InviteQrHero(
    summary: IdentitySummary,
    activeName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .testTag("settings.invite_qr_hero"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(8.dp),
        ) {
            OnymQrCode(value = summary.inviteUrl(), size = 92.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_invite_qr_eyebrow).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.settings_invite_qr_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_invite_qr_subtitle, activeName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
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
