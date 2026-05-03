package chat.onym.android.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.onym.android.R

/**
 * Settings → Relayer → Run your own relayer. Self-host explainer
 * with shell snippets and one-click deploy targets. Links point at
 * `github.com/onymchat/onym-relayer`.
 *
 * Scaffold: shell snippets are static strings copyable to the
 * clipboard; the deploy buttons open the host provider's deploy
 * URL in the browser. No on-device deploy mechanic — the user
 * runs the commands on their own machine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunRelayerScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var copiedKey by remember { mutableStateOf<String?>(null) }

    // stringResource() is @Composable; hoist these out of LazyColumn's
    // (non-Composable) content lambda.
    val step1Title = stringResource(R.string.run_relayer_step1_title)
    val step1Body = stringResource(R.string.run_relayer_step1_body)
    val step2Title = stringResource(R.string.run_relayer_step2_title)
    val step2Body = stringResource(R.string.run_relayer_step2_body)
    val step3Title = stringResource(R.string.run_relayer_step3_title)
    val step3Body = stringResource(R.string.run_relayer_step3_body)
    val step4Title = stringResource(R.string.run_relayer_step4_title)
    val step4Body = stringResource(R.string.run_relayer_step4_body)
    val steps = listOf(
        StepData(1, step1Title, step1Body, "git clone https://github.com/onymchat/onym-relayer\ncd onym-relayer"),
        StepData(2, step2Title, step2Body, "cp .env.example .env\necho \"RELAYER_URL=https://your-domain\" >> .env"),
        StepData(3, step3Title, step3Body, "fly launch --copy-config\nfly deploy"),
        StepData(4, step4Title, step4Body, null),
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.run_relayer_title)) },
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
                .testTag("run_relayer.list"),
        ) {
            // ─── Hero ─────────────────────────────────────────────
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
                            text = "github.com/onymchat/onym-relayer",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.run_relayer_hero_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.run_relayer_hero_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DarkHeroButton(
                            label = stringResource(R.string.run_relayer_view_github),
                            primary = true,
                            onClick = { openUrl(context, "https://github.com/onymchat/onym-relayer") },
                        )
                        DarkHeroButton(
                            label = stringResource(R.string.run_relayer_read_docs),
                            primary = false,
                            onClick = { openUrl(context, "https://github.com/onymchat/onym-relayer#readme") },
                        )
                    }
                }
            }

            // ─── Numbered steps ───────────────────────────────────
            steps.forEachIndexed { idx, step ->
                item {
                    NumberedStep(
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

            // ─── One-click deploy ─────────────────────────────────
            item { SettingsSectionLabel(stringResource(R.string.run_relayer_deploy_section)) }
            item {
                SettingsCard {
                    SettingsRow(
                        leading = { SettingsTileLabel("FLY", Color(0xFF7B3FE4)) },
                        title = stringResource(R.string.run_relayer_deploy_fly),
                        subtitle = stringResource(R.string.run_relayer_deploy_fly_subtitle),
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = { openUrl(context, "https://fly.io/launch") },
                    )
                    SettingsRow(
                        leading = { SettingsTileLabel("RWY", Color(0xFF1F1F1F)) },
                        title = stringResource(R.string.run_relayer_deploy_railway),
                        subtitle = stringResource(R.string.run_relayer_deploy_railway_subtitle),
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = { openUrl(context, "https://railway.app/new") },
                    )
                    SettingsRow(
                        leading = { SettingsTileLabel("DKR", Color(0xFF0080FF)) },
                        title = stringResource(R.string.run_relayer_deploy_docker),
                        subtitle = stringResource(R.string.run_relayer_deploy_docker_subtitle),
                        showChevron = false,
                        trailing = { ExternalGlyph() },
                        onClick = { openUrl(context, "https://github.com/onymchat/onym-relayer#docker") },
                        isLast = true,
                    )
                }
            }
            item { SettingsFootnote(stringResource(R.string.run_relayer_footnote)) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun DarkHeroButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) Color(0xFF0A0A0C) else Color.White,
        )
    }
}

@Composable
private fun NumberedStep(
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
                    text = n.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
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
                            text = cmd,
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
                                .clickable(onClick = onCopy),
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
        if (showConnector) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .padding(start = 13.dp)
                    .size(width = 2.dp, height = 18.dp)
                    .background(SettingsTile.Blue.copy(alpha = 0.25f)),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun ExternalGlyph() {
    Icon(
        Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
    )
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) { }
}

internal fun copyToClipboard(context: Context, label: String, value: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

private data class StepData(val n: Int, val title: String, val body: String, val cmd: String?)
