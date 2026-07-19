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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Inventory2
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

/** One numbered step in a [SelfHostGuideScreen]. */
data class SelfHostStep(val n: Int, val title: String, val body: String, val cmd: String?)

/**
 * Reusable "run your own <server>" tutorial: a dark hero with a
 * prominent generic / not-Onym-software note, numbered copyable
 * Docker/CLI steps, and a footnote. Backs the Nostr-relay and
 * Blossom-server self-host guides — the servers are standard,
 * interoperable open-source software; nothing here is Onym-specific.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfHostGuideScreen(
    title: String,
    heroTitle: String,
    heroBody: String,
    genericNote: String,
    steps: List<SelfHostStep>,
    footnote: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var copiedKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
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
                .testTag("self_host.list"),
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
                    // Prominent "generic, not Onym" badge.
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Inventory2, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Text(
                            genericNote,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        heroTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        heroBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            steps.forEachIndexed { idx, step ->
                item {
                    SelfHostNumberedStep(
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

            item { SettingsFootnote(footnote) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun SelfHostNumberedStep(
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

// ─── Content ──────────────────────────────────────────────────────

/** Generic Nostr-relay self-host guide (strfry example). */
@Composable
fun RunNostrRelayScreen(onBack: () -> Unit) {
    SelfHostGuideScreen(
        title = "Run your own Nostr relay",
        heroTitle = "Run your own Nostr relay",
        heroBody = "A Nostr relay is standard open-source software that speaks the Nostr " +
            "protocol — nothing Onym-specific. The steps below use strfry, a popular relay; " +
            "nostr-rs-relay is another good option.",
        genericNote = "Generic Nostr software — not Onym",
        steps = listOf(
            SelfHostStep(
                1, "Run a relay with Docker",
                "strfry listens on port 7777. Data persists in a named volume.",
                "docker run -d --name strfry \\\n" +
                    "  -p 7777:7777 \\\n" +
                    "  -v strfry-db:/app/strfry-db \\\n" +
                    "  dockurr/strfry",
            ),
            SelfHostStep(
                2, "Put it behind TLS",
                "Clients connect over wss://, so terminate TLS with a reverse proxy on your " +
                    "domain (Caddy auto-issues a certificate).",
                "caddy reverse-proxy \\\n  --from relay.example.com \\\n  --to localhost:7777",
            ),
            SelfHostStep(
                3, "Add it to Onym",
                "Back on Nostr Relays, paste wss://relay.example.com into \"Add Custom URL\".",
                null,
            ),
        ),
        footnote = "Any spec-compliant Nostr relay works (strfry, nostr-rs-relay, and " +
            "others). These are independent open-source projects, not Onym software.",
        onBack = onBack,
    )
}

/** Generic Blossom-server self-host guide (blossom-server example). */
@Composable
fun RunBlossomServerScreen(onBack: () -> Unit) {
    SelfHostGuideScreen(
        title = "Run your own Blossom server",
        heroTitle = "Run your own Blossom server",
        heroBody = "Blossom is an open spec for storing media blobs addressed by hash — " +
            "nothing Onym-specific. The steps below use blossom-server (hzrd149), a common " +
            "implementation.",
        genericNote = "Generic Blossom software — not Onym",
        steps = listOf(
            SelfHostStep(
                1, "Run a server with Docker",
                "blossom-server listens on port 3000. Blobs + config persist in a mounted folder.",
                "docker run -d --name blossom \\\n" +
                    "  -p 3000:3000 \\\n" +
                    "  -v ./blossom-data:/app/data \\\n" +
                    "  ghcr.io/hzrd149/blossom-server:master",
            ),
            SelfHostStep(
                2, "Put it behind TLS",
                "Onym uploads/downloads over https://, so front it with a reverse proxy on " +
                    "your domain (Caddy auto-issues a certificate).",
                "caddy reverse-proxy \\\n  --from blossom.example.com \\\n  --to localhost:3000",
            ),
            SelfHostStep(
                3, "Add it to Onym",
                "Back on Blossom Relays, paste https://blossom.example.com into \"Add Custom URL\".",
                null,
            ),
        ),
        footnote = "Any Blossom-compliant server works. These are independent open-source " +
            "projects, not Onym software.",
        onBack = onBack,
    )
}
