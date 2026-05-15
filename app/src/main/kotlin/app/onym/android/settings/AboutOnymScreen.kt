package app.onym.android.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.BuildConfig
import app.onym.android.R
import app.onym.android.group.OnymMark

/**
 * About Onym — version, GitHub source links, and contact info.
 *
 * Repository links point at the **onymchat** GitHub org (single
 * source of truth as of 2026-05). The design's `onym/onym-ios`
 * placeholders are corrected here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutOnymScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            // No browser on the device — silently no-op rather than crash.
        }
    }
    fun sendMail(addr: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr"))
            )
        } catch (_: Throwable) { }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about_screen_title)) },
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
                .testTag("about.list"),
        ) {
            // ─── Hero ────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF1B1F24), Color(0xFF0D1117)),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        OnymMark(size = 64.dp, color = Color.White)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.brand_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.about_version_pill,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE.toString(),
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ─── VERSION ─────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.about_version_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.about_version_label),
                        showChevron = false,
                        trailing = {
                            Text(
                                text = BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        insetHairline = 16.dp,
                    )
                    SettingsRow(
                        title = stringResource(R.string.about_build_label),
                        showChevron = false,
                        trailing = {
                            Text(
                                text = BuildConfig.VERSION_CODE.toString(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        insetHairline = 16.dp,
                        isLast = true,
                    )
                }
            }

            // ─── RESOURCES ───────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.about_resources_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Code, SettingsTile.GitHub) },
                        title = stringResource(R.string.about_source_android),
                        subtitle = "github.com/onymchat/onym-android",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat/onym-android") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Code, SettingsTile.GitHub) },
                        title = stringResource(R.string.about_source_ios),
                        subtitle = "github.com/onymchat/onym-ios",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat/onym-ios") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Code, SettingsTile.Indigo) },
                        title = stringResource(R.string.about_source_contracts),
                        subtitle = "github.com/onymchat/onym-contracts",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat/onym-contracts") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Code, SettingsTile.Indigo) },
                        title = stringResource(R.string.about_source_relayer),
                        subtitle = "github.com/onymchat/onym-relayer",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat/onym-relayer") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Description, SettingsTile.Indigo) },
                        title = stringResource(R.string.about_documentation),
                        subtitle = stringResource(R.string.about_documentation_subtitle),
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.AutoMirrored.Filled.Article, SettingsTile.Green) },
                        title = stringResource(R.string.about_whitepaper),
                        subtitle = stringResource(R.string.about_whitepaper_subtitle),
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat/onym-paper") },
                        isLast = true,
                    )
                }
            }

            // ─── HELP ────────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.about_help_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.AutoMirrored.Filled.HelpOutline, SettingsTile.Blue) },
                        title = stringResource(R.string.about_issues_title),
                        subtitle = stringResource(R.string.about_issues_subtitle),
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/onymchat/onym-android/issues") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Forum, SettingsTile.Green) },
                        title = stringResource(R.string.about_discussions_title),
                        subtitle = stringResource(R.string.about_discussions_subtitle),
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { openUrl("https://github.com/orgs/onymchat/discussions") },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Email, SettingsTile.Orange) },
                        title = stringResource(R.string.about_contact_title),
                        subtitle = "lead@onym.app",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { sendMail("lead@onym.app") },
                        isLast = true,
                    )
                }
            }

            // ─── Credits ─────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 36.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OnymMark(
                        size = 22.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                    Text(
                        text = stringResource(R.string.about_credits_line1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text(
                        text = stringResource(R.string.about_credits_line2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text(
                        text = stringResource(R.string.about_credits_copyright),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}
