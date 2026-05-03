package chat.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.onym.android.R

/**
 * Settings tab — entry point for the recovery-phrase backup flow.
 * Minimal first cut: one Security section with the Backup row that
 * navigates to [chat.onym.android.recovery.RecoveryPhraseBackupScreen].
 *
 * Mirrors `SettingsView.swift` from onym-ios PR #6 (PR #6 there, not
 * the onym-android #6). More sections (preferences, advanced, about)
 * land as the app grows.
 *
 * UX choices vs iOS:
 *
 *   - `LargeTopAppBar` = SwiftUI's `.navigationTitle("Settings")` in
 *     iOS 16+ default (large title that collapses on scroll).
 *   - `ListItem` + `headlineContent`/`leadingContent`/`trailingContent`
 *     = the iOS Form row shape.
 *   - The orange key-icon RoundedRect ([SettingsIconBox]) is the same
 *     atom the recovery-flow Intro screen's rules list uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackupClick: () -> Unit,
    onRelayerClick: () -> Unit,
    onAnchorsClick: () -> Unit,
    onIdentitiesClick: () -> Unit,
    /** App-wide network preference. Bound to the Settings → Network
     *  → "Use Mainnet" Switch. */
    useMainnet: Boolean,
    onToggleMainnet: (Boolean) -> Unit,
) {
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
            modifier = Modifier.testTag("settings.list"),
        ) {
            // Groups section moved to the Chats tab (PR-30) — Settings
            // now opens straight to the Security section.
            item {
                SectionHeader(stringResource(R.string.security))
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.backup_recovery_phrase))
                    },
                    leadingContent = {
                        SettingsIconBox(
                            icon = Icons.Filled.Key,
                            background = Color(0xFFFF9500),
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBackupClick),
                )
            }
            item {
                Text(
                    stringResource(R.string.security_footer_view_recovery_phrase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            // Identities — multi-identity management (PR-5).
            item { SectionHeader("Identities") }
            item {
                ListItem(
                    headlineContent = { Text("Manage identities") },
                    leadingContent = {
                        SettingsIconBox(
                            icon = Icons.Filled.Person,
                            background = Color(0xFFAF52DE),
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onIdentitiesClick)
                        .testTag("settings.identities_row"),
                )
            }

            // Network section — relayer URL config + anchor contract
            // versions. Localized via res/values/ + values-ru/ per
            // PR #21's catalog work.
            item { SectionHeader(stringResource(R.string.settings_network)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.relayer_title)) },
                    leadingContent = {
                        SettingsIconBox(
                            icon = Icons.Filled.Cloud,
                            background = Color(0xFF34C759),
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onRelayerClick)
                        .testTag("settings.relayer_row"),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.anchors_title)) },
                    leadingContent = {
                        SettingsIconBox(
                            icon = Icons.Filled.Anchor,
                            background = Color(0xFF5856D6),
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onAnchorsClick)
                        .testTag("settings.anchors_row"),
                )
            }
            // Use Mainnet toggle (PR-C follow-up). Persisted in
            // DataStore under `onym.useMainnet`; flipping here changes
            // the network the next Create Group flow will use.
            // Existing groups keep whatever network they were created on.
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_use_mainnet)) },
                    leadingContent = {
                        SettingsIconBox(
                            icon = if (useMainnet) Icons.Filled.Public else Icons.Filled.Build,
                            background = if (useMainnet) Color(0xFF34C759) else Color(0xFF8E8E93),
                        )
                    },
                    trailingContent = {
                        Switch(checked = useMainnet, onCheckedChange = onToggleMainnet)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleMainnet(!useMainnet) }
                        .testTag("settings.use_mainnet_toggle"),
                )
            }
            item {
                Text(
                    stringResource(
                        if (useMainnet) R.string.settings_use_mainnet_on_footer
                        else R.string.settings_use_mainnet_off_footer,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 32.dp)
            .padding(top = 16.dp, bottom = 6.dp),
    )
}

/**
 * 30dp coloured RoundedRect with a centred Icon inside. Matches the
 * iOS `SettingsIconBox` and the recovery-flow `RoundedIcon` from the
 * Intro screen.
 */
@Composable
private fun SettingsIconBox(icon: ImageVector, background: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(15.dp),
        )
    }
}
