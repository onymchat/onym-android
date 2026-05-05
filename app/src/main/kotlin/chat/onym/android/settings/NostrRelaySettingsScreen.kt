package chat.onym.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.transport.nostr.NostrRelayEndpoint

/**
 * Settings → Transport → Nostr Relays. Mirrors
 * `NostrRelaySettingsView.swift` from onym-ios PR #87.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrRelaySettingsScreen(
    viewModel: NostrRelaySettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nostr Relays") },
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
            modifier = Modifier.padding(padding).testTag("nostr.list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ConfiguredCard(
                    endpoints = state.snapshot.endpoints,
                    onRemove = viewModel::tappedRemove,
                )
            }
            item {
                AddCustomCard(
                    draft = state.customDraft,
                    error = state.customDraftError,
                    onChange = viewModel::customDraftChanged,
                    onAdd = viewModel::tappedAddCustom,
                )
            }
            item {
                SectionFootnote(
                    "Use a private deployment, localhost, or any Nostr relay you trust. " +
                        "URLs must use the wss:// (or ws://) scheme.",
                )
            }
            item {
                ResetCard(onReset = viewModel::tappedResetToDefault)
            }
            item {
                SectionFootnote(
                    "Changes apply on the next app launch. The inbox transport reads " +
                        "relays once at boot.",
                )
            }
        }
    }
}

@Composable
private fun ConfiguredCard(
    endpoints: List<NostrRelayEndpoint>,
    onRemove: (String) -> Unit,
) {
    SectionLabel(text = "CONFIGURED · ${endpoints.size}")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (endpoints.isEmpty()) {
            Text(
                text = "No relays configured.",
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("nostr.configured.empty"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            endpoints.forEachIndexed { idx, endpoint ->
                EndpointRow(endpoint = endpoint, onRemove = { onRemove(endpoint.url) })
                if (idx != endpoints.lastIndex) HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun EndpointRow(
    endpoint: NostrRelayEndpoint,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(endpoint.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (endpoint.isDefault) {
                    Spacer(Modifier.size(6.dp))
                    DefaultPill()
                }
            }
            Text(
                text = endpoint.url,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.testTag("nostr.configured.remove.${endpoint.url}"),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove")
        }
    }
}

@Composable
private fun AddCustomCard(
    draft: String,
    error: String?,
    onChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    SectionLabel(text = "ADD CUSTOM URL")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onChange,
            placeholder = { Text("wss://relay.example.com") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nostr.add.custom_url_field"),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.None,
            ),
            isError = error != null,
        )
        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.testTag("nostr.add.custom_error"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nostr.add.custom_button"),
        ) {
            Text("Add")
        }
    }
}

@Composable
private fun ResetCard(onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .testTag("nostr.reset_default"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Restore default", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = "Re-seed the configuration with the Onym Official relay.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun DefaultPill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            "DEFAULT",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionFootnote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
    )
}
