package chat.onym.android.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import chat.onym.android.R
import chat.onym.android.chain.ContractNetwork
import chat.onym.android.chain.GovernanceType
import kotlinx.coroutines.delay

/**
 * Anchors → Custom → Deploy from source. Scaffolds the build & deploy
 * flow against `github.com/onymchat/onym-contracts`. Real deployment
 * isn't wired here — the "Build & Deploy" button replays a canned
 * progress-log animation and reveals a stub deployed-contract address
 * card. The CLI fallback snippet at the bottom is always available
 * for users who'd rather deploy off-device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployContractScreen(
    network: ContractNetwork,
    type: GovernanceType,
    onBack: () -> Unit,
    onUseDeployed: (address: String) -> Unit,
) {
    val context = LocalContext.current
    var ref by remember { mutableStateOf("main") }
    var stage by remember { mutableStateOf(DeployStage.Idle) }
    var progress by remember { mutableStateOf(0) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var deployedAddr by remember { mutableStateOf<String?>(null) }
    var copiedCli by remember { mutableStateOf(false) }
    var copiedAddr by remember { mutableStateOf(false) }

    val networkPassphrase = if (network == ContractNetwork.Testnet) {
        "Test SDF Network ; September 2015"
    } else "Public Global Stellar Network ; September 2015"

    val cliCmd = "soroban contract deploy \\\n" +
        "  --network ${if (network == ContractNetwork.Testnet) "testnet" else "public"} \\\n" +
        "  --source-account onym-deploy \\\n" +
        "  --wasm onym_${type.wireValue}.wasm"

    LaunchedEffect(stage) {
        if (stage != DeployStage.Building) return@LaunchedEffect
        val netName = if (network == ContractNetwork.Testnet) "testnet" else "public"
        data class Step(
            val gapMs: Long,
            val log: String,
            val percent: Int,
            val nextStage: DeployStage? = null,
            val isUpload: Boolean = false,
        )
        val steps = listOf(
            Step(600, "Cloned onymchat/onym-contracts @ $ref", 12),
            Step(800, "cargo build --release --target wasm32", 38),
            Step(800, "Built onym_${type.wireValue}.wasm (47 KB)", 56, DeployStage.Deploying),
            Step(800, "stellar.$netName · uploading wasm", 72, isUpload = true),
            Step(700, "stellar.$netName · invoking deploy", 88, isUpload = true),
            Step(700, "Deployed", 100, DeployStage.Done),
        )
        for (step in steps) {
            delay(step.gapMs)
            val prefix = if (step.isUpload) "↗" else "✓"
            logs = logs + "$prefix ${step.log}"
            progress = step.percent
            step.nextStage?.let { stage = it }
            if (step.percent == 100) {
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ234567"
                deployedAddr = buildString {
                    append('C')
                    repeat(55) { append(alphabet[(alphabet.indices).random()]) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.deploy_contract_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = stage == DeployStage.Idle || stage == DeployStage.Done,
                    ) {
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
            modifier = Modifier.fillMaxSize().testTag("deploy_contract.list"),
        ) {
            // ─── Hero (dark) ──────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1B1F24), Color(0xFF0D1117)),
                            )
                        )
                        .padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            Icons.Filled.Code,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "github.com/onymchat/onym-contracts",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.deploy_contract_hero_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.deploy_contract_hero_body,
                            stringResource(type.displayNameResId).lowercase(),
                            stringResource(network.displayNameResId),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            // ─── SOURCE ───────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.deploy_contract_source_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileBox(Icons.Filled.Code, SettingsTile.GitHub) },
                        title = stringResource(R.string.deploy_contract_repo),
                        subtitle = "github.com/onymchat/onym-contracts",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = { openUrl(context, "https://github.com/onymchat/onym-contracts") },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsTileLabel("REF", SettingsTile.Indigo)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.deploy_contract_ref_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = ref,
                                onValueChange = { ref = it },
                                enabled = stage == DeployStage.Idle,
                                singleLine = true,
                                placeholder = { Text("main · v0.0.5 · commit sha") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("deploy_contract.ref"),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                    SettingsHairline(insetStart = 16.dp)
                    SettingsRow(
                        leading = { SettingsTileLabel("T", SettingsTile.Green) },
                        title = stringResource(R.string.deploy_contract_network_label),
                        showChevron = false,
                        trailing = {
                            Text(
                                text = stringResource(network.displayNameResId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    SettingsRow(
                        leading = {
                            SettingsTileLabel(
                                stringResource(type.displayNameResId).take(2).uppercase(),
                                SettingsTile.Purple,
                            )
                        },
                        title = stringResource(R.string.deploy_contract_module_label),
                        showChevron = false,
                        trailing = {
                            Text(
                                text = stringResource(type.displayNameResId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        isLast = true,
                    )
                }
            }

            // ─── Build & Deploy button ────────────────────────────
            if (stage == DeployStage.Idle) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(
                            onClick = { stage = DeployStage.Building },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("deploy_contract.start"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                Icons.Filled.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.deploy_contract_action),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }

            // ─── Build/Deploy log ─────────────────────────────────
            if (stage != DeployStage.Idle) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0D1117))
                            .padding(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (stage == DeployStage.Done) SettingsTile.Green else SettingsTile.Amber
                                    ),
                            )
                            Text(
                                text = when (stage) {
                                    DeployStage.Building -> stringResource(R.string.deploy_contract_log_building)
                                    DeployStage.Deploying -> stringResource(R.string.deploy_contract_log_deploying)
                                    DeployStage.Done -> stringResource(R.string.deploy_contract_log_done)
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                            Box(modifier = Modifier.weight(1f))
                            Text(
                                text = "$progress%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.4f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = if (stage == DeployStage.Done) SettingsTile.Green else SettingsTile.Blue,
                            trackColor = Color.White.copy(alpha = 0.08f),
                        )
                        Spacer(Modifier.height(12.dp))
                        logs.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = when {
                                    line.startsWith("✓") -> Color(0xFFA6FF99)
                                    line.startsWith("↗") -> Color(0xFF7CC2FF)
                                    else -> Color.White
                                },
                            )
                        }
                    }
                }
            }

            // ─── Deployed contract card ───────────────────────────
            if (stage == DeployStage.Done && deployedAddr != null) {
                item { SettingsSectionLabel(stringResource(R.string.deploy_contract_deployed_section)) }
                item {
                    SettingsCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(SettingsTile.Green),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.deploy_contract_deployed_title),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = deployedAddr!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                        SettingsHairline(insetStart = 16.dp)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        copyToClipboard(context, "Contract address", deployedAddr!!)
                                        copiedAddr = true
                                    }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        if (copiedAddr) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        tint = SettingsTile.Blue,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = if (copiedAddr) stringResource(R.string.copied) else stringResource(R.string.copy),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = SettingsTile.Blue,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(0.5.dp)
                                    .height(46.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val host = if (network == ContractNetwork.Testnet) {
                                            "testnet.stellar.expert"
                                        } else "stellar.expert"
                                        val net = if (network == ContractNetwork.Testnet) "testnet" else "public"
                                        openUrl(context, "https://$host/explorer/$net/contract/${deployedAddr}")
                                    }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.deploy_contract_view_explorer),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = SettingsTile.Blue,
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = SettingsTile.Blue,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(
                            onClick = { onUseDeployed(deployedAddr!!) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("deploy_contract.use"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.deploy_contract_use_button),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }

            // ─── CLI fallback ─────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.deploy_contract_cli_section)) }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D1117))
                        .padding(start = 12.dp, end = 36.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = cliCmd,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFFA6FF99),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                copyToClipboard(context, "CLI command", cliCmd)
                                copiedCli = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (copiedCli) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = if (copiedCli) Color(0xFFA6FF99) else Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            item {
                SettingsFootnote(
                    stringResource(R.string.deploy_contract_passphrase_footnote, networkPassphrase)
                )
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private enum class DeployStage { Idle, Building, Deploying, Done }
