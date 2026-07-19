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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.GovernanceType

/**
 * Anchors → Custom → Deploy from source.
 *
 * Contracts can't be deployed from the phone — building the wasm needs
 * the Rust + Stellar CLI toolchain, and deploy is a privileged on-chain
 * op the app has no direct RPC path for. So this is an honest, copyable
 * guide to deploying `onymchat/onym-contracts` from a computer with the
 * `stellar` CLI, then pasting the resulting address back via "Use
 * existing address". (Previously this screen faked a build/deploy run.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployContractScreen(
    network: ContractNetwork,
    type: GovernanceType,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var copiedKey by remember { mutableStateOf<String?>(null) }
    val cliNetwork = if (network == ContractNetwork.Testnet) "testnet" else "mainnet"

    val steps = listOf(
        DeployStep(
            1, "Clone the contracts repo", "The Onym contracts are open source.",
            "git clone https://github.com/onymchat/onym-contracts\ncd onym-contracts",
        ),
        DeployStep(
            2, "Install the Stellar CLI", "Needs the Rust toolchain + the wasm32 target.",
            "cargo install --locked stellar-cli",
        ),
        DeployStep(
            3, "Build the wasm", "Compiles the contracts to a wasm artifact.",
            "stellar contract build",
        ),
        DeployStep(
            4, "Deploy to $cliNetwork",
            "Sign with your own funded account; prints a C… contract address.",
            "stellar contract deploy \\\n" +
                "  --network $cliNetwork \\\n" +
                "  --source-account onym-deploy \\\n" +
                "  --wasm target/wasm32-unknown-unknown/release/onym_${type.wireValue}.wasm",
        ),
        DeployStep(
            5, "Add it to Onym",
            "Copy the C… address it prints, then go back and tap \"Use existing address\" to paste it in.",
            null,
        ),
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Deploy from source") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .testTag("deploy.list"),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF1B1F24), Color(0xFF0D1117))))
                        .padding(20.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text(
                            "github.com/onymchat/onym-contracts",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Deploy your own contract",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Build and deploy the Onym contracts from your computer with the Stellar CLI, then point Onym at the deployed address.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .clickable { openUrl(context, "https://github.com/onymchat/onym-contracts") }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "View on GitHub",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF0A0A0C),
                        )
                    }
                }
            }

            steps.forEachIndexed { idx, step ->
                item {
                    DeployNumberedStep(
                        n = step.n,
                        title = step.title,
                        body = step.body,
                        cmd = step.cmd,
                        copied = copiedKey == "step${step.n}",
                        onCopy = {
                            step.cmd?.let {
                                copyToClipboard(context, "Step ${step.n}", it)
                                copiedKey = "step${step.n}"
                            }
                        },
                        showConnector = idx < steps.size - 1,
                    )
                }
            }

            item {
                SettingsFootnote(
                    "Deployment runs on your computer, not on the phone — the app can't " +
                        "build wasm or submit a deploy transaction directly.",
                )
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private data class DeployStep(val n: Int, val title: String, val body: String, val cmd: String?)

@Composable
private fun DeployNumberedStep(
    n: Int,
    title: String,
    body: String,
    cmd: String?,
    copied: Boolean,
    onCopy: () -> Unit,
    showConnector: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SettingsTile.Blue),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    n.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cmd != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0D1117))
                            .padding(start = 12.dp, end = 36.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        Text(
                            cmd,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFFA6FF99),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(start = 8.dp)
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = onCopy)
                                .testTag("deploy.copy.step$n"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = null,
                                tint = if (copied) Color(0xFFA6FF99) else Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (showConnector) {
            Box(
                modifier = Modifier
                    .padding(start = 13.dp)
                    .size(width = 2.dp, height = 18.dp)
                    .background(SettingsTile.Blue.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
