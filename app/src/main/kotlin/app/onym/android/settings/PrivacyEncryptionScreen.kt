package app.onym.android.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.R

/**
 * Privacy & Encryption — read-only explainer of how Onym keeps
 * messages private. Stack: a green "everything is encrypted" hero,
 * a "How it works" group with the three protocol guarantees, and a
 * "Your keys" group that names the cryptographic primitives derived
 * from the user's seed.
 *
 * No toggles or destructive actions live here yet — the design's
 * read-receipts / Face-ID / cache-clear surfaces require backing
 * features the app doesn't expose; they'll land alongside those
 * features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyEncryptionScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.privacy_screen_title)) },
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
                .testTag("privacy.list"),
        ) {
            // ─── Encryption status hero ───────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFDFFAEA), Color(0xFFB6F0CD)),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = SettingsTile.Green,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.privacy_hero_title),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.privacy_hero_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ─── How it works ─────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.privacy_how_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Key, SettingsTile.Purple) },
                        title = stringResource(R.string.privacy_e2e_title),
                        subtitle = stringResource(R.string.privacy_e2e_subtitle),
                        showChevron = false,
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.VisibilityOff, SettingsTile.Indigo) },
                        title = stringResource(R.string.privacy_anon_title),
                        subtitle = stringResource(R.string.privacy_anon_subtitle),
                        showChevron = false,
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Star, SettingsTile.Green) },
                        title = stringResource(R.string.privacy_verifiable_title),
                        subtitle = stringResource(R.string.privacy_verifiable_subtitle),
                        showChevron = false,
                        isLast = true,
                    )
                }
            }

            // ─── Your keys ────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.privacy_keys_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileLabel("39", SettingsTile.Gray) },
                        title = stringResource(R.string.privacy_keys_bip39),
                        subtitle = stringResource(R.string.privacy_keys_bip39_subtitle),
                        showChevron = false,
                    )
                    SettingsRow(
                        leading = { SettingsTileLabel("npub", SettingsTile.Indigo) },
                        title = stringResource(R.string.privacy_keys_nostr),
                        subtitle = stringResource(R.string.privacy_keys_nostr_subtitle),
                        showChevron = false,
                    )
                    SettingsRow(
                        leading = { SettingsTileLabel("BLS", SettingsTile.Purple) },
                        title = stringResource(R.string.privacy_keys_bls),
                        subtitle = stringResource(R.string.privacy_keys_bls_subtitle),
                        showChevron = false,
                    )
                    SettingsRow(
                        leading = { SettingsTileLabel("XLM", SettingsTile.Orange) },
                        title = stringResource(R.string.privacy_keys_stellar),
                        subtitle = stringResource(R.string.privacy_keys_stellar_subtitle),
                        showChevron = false,
                        isLast = true,
                    )
                }
            }
            item { SettingsFootnote(stringResource(R.string.privacy_keys_footnote)) }

            // ─── Transport ────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.privacy_transport_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Lock, SettingsTile.Blue) },
                        title = stringResource(R.string.privacy_transport_relayer_title),
                        subtitle = stringResource(R.string.privacy_transport_relayer_subtitle),
                        showChevron = false,
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Public, SettingsTile.Orange) },
                        title = stringResource(R.string.privacy_transport_chain_title),
                        subtitle = stringResource(R.string.privacy_transport_chain_subtitle),
                        showChevron = false,
                        isLast = true,
                    )
                }
            }
            item { SettingsFootnote(stringResource(R.string.privacy_transport_footnote)) }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
