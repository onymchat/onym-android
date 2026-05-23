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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.R
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.GovernanceType
import app.onym.android.group.OnymMark

/**
 * Anchors → version → Contract Detail. Shows the deployed contract
 * address, links to Stellar Expert, the source on
 * `github.com/onymchat/onym-contracts`, and the audit status (pending
 * — no audits yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractDetailScreen(
    network: ContractNetwork,
    type: GovernanceType,
    version: String,
    contractAddress: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(version) },
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
                .testTag("contract_detail.list"),
        ) {
            // ─── Hero ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFD14B00).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        OnymMark(size = 32.dp, color = Color(0xFFD14B00))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.contract_detail_hero_eyebrow, stringResource(type.displayNameResId)).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = version,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.contract_detail_hero_subtitle,
                                stringResource(network.displayNameResId),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ─── ON-CHAIN ─────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.contract_detail_onchain_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileLabel("SX", SettingsTile.Indigo) },
                        title = stringResource(R.string.contract_detail_explorer_title),
                        subtitle = if (network == ContractNetwork.Testnet) "testnet.stellar.expert" else "stellar.expert",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = {
                            val host = if (network == ContractNetwork.Testnet) {
                                "testnet.stellar.expert"
                            } else "stellar.expert"
                            val tail = contractAddress?.let { "/explorer/${if (network == ContractNetwork.Testnet) "testnet" else "public"}/contract/$it" }
                                ?: "/"
                            openUrl(context, "https://$host$tail")
                        },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.ContentCopy, SettingsTile.Gray) },
                        title = stringResource(R.string.contract_detail_copy_address),
                        subtitle = contractAddress ?: stringResource(R.string.contract_detail_no_address),
                        subtitleMono = true,
                        showChevron = false,
                        onClick = {
                            contractAddress?.let { copyToClipboard(context, "Contract address", it) }
                        },
                        isLast = true,
                    )
                }
            }

            // ─── SOURCE ───────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.contract_detail_source_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Code, SettingsTile.GitHub) },
                        title = stringResource(R.string.contract_detail_view_source),
                        subtitle = "github.com/onymchat/onym-contracts @ $version",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = {
                            openUrl(
                                context,
                                "https://github.com/onymchat/onym-contracts/releases/tag/$version",
                            )
                        },
                    )
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Warning, SettingsTile.Amber) },
                        title = stringResource(R.string.contract_detail_audit_title),
                        subtitle = stringResource(R.string.contract_detail_audit_subtitle),
                        showChevron = false,
                        isLast = true,
                    )
                }
            }
            item {
                SettingsFootnote(
                    stringResource(
                        R.string.contract_detail_footnote,
                        stringResource(type.displayNameResId).lowercase(),
                    )
                )
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
