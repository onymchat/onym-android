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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.onym.android.R
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.GovernanceType

/**
 * Anchors → Custom → Use existing address. Lets the user point new
 * `(network, governance)` chats at a Stellar Soroban contract they
 * already deployed. Validates the C-prefixed 56-char address shape
 * client-side; the actual on-chain probe is a stub here (the
 * "Verify" button shows a green confirmation card unconditionally
 * once the format check passes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UseExistingContractScreen(
    network: ContractNetwork,
    type: GovernanceType,
    onBack: () -> Unit,
    onUseContract: (address: String, label: String) -> Unit,
) {
    val context = LocalContext.current
    var addr by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(false) }

    val cleanAddr = addr.trim()
    val looksValid = cleanAddr.matches(Regex("^C[A-Z0-9]{55}$"))

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.use_contract_title)) },
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
            modifier = Modifier.fillMaxSize().testTag("use_contract.list"),
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
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFE5E5FE), Color(0xFFC7C7F4)),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.AccountTree,
                            contentDescription = null,
                            tint = SettingsTile.Indigo,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.use_contract_hero_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(
                                R.string.use_contract_hero_body,
                                stringResource(type.displayNameResId).lowercase(),
                                stringResource(network.displayNameResId),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ─── Stellar contract address ─────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.use_contract_address_section)) }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        OutlinedTextField(
                            value = addr,
                            onValueChange = { addr = it.replace("\\s".toRegex(), "").uppercase(); verified = false },
                            placeholder = { Text("C…") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("use_contract.address.input"),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrect = false,
                            ),
                        )
                        if (cleanAddr.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (looksValid) {
                                        stringResource(R.string.use_contract_validity_ok)
                                    } else {
                                        stringResource(R.string.use_contract_validity_progress, cleanAddr.length)
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (looksValid) SettingsTile.Green else SettingsTile.Red,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            (if (looksValid) SettingsTile.Green else SettingsTile.Red).copy(alpha = 0.14f)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                                Text(
                                    text = stringResource(R.string.use_contract_address_helper),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ─── Label ────────────────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.use_contract_label_section)) }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { if (it.length <= 30) label = it },
                            placeholder = { Text(stringResource(R.string.use_contract_label_placeholder)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("use_contract.label.input"),
                            singleLine = true,
                        )
                    }
                }
            }
            item { SettingsFootnote(stringResource(R.string.use_contract_label_footnote)) }

            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            if (!verified) verified = true
                            else onUseContract(cleanAddr, label.ifBlank { "Custom contract" })
                        },
                        enabled = looksValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("use_contract.verify"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = if (verified) stringResource(R.string.use_contract_use_button)
                            else stringResource(R.string.use_contract_verify_button),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }

            if (verified) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SettingsTile.Green.copy(alpha = 0.1f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(11.dp))
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.use_contract_verified_title),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFF175E2F),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.use_contract_verified_body),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF306E48),
                            )
                        }
                    }
                }
            }

            // ─── How to find it ───────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.use_contract_findit_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileLabel("SX", SettingsTile.Indigo) },
                        title = stringResource(R.string.use_contract_findit_explorer),
                        subtitle = if (network == ContractNetwork.Testnet) "testnet.stellar.expert" else "stellar.expert",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = {
                            val host = if (network == ContractNetwork.Testnet) "testnet.stellar.expert" else "stellar.expert"
                            openUrl(context, "https://$host/")
                        },
                    )
                    SettingsRow(
                        leading = { SettingsTileLabel("CLI", SettingsTile.Gray) },
                        title = stringResource(R.string.use_contract_findit_cli),
                        subtitle = "soroban contract id …",
                        subtitleMono = true,
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = { openUrl(context, "https://github.com/onymchat/onym-contracts#deploy") },
                        isLast = true,
                    )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
