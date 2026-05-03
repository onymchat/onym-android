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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.identity.IdentitiesViewModel
import chat.onym.android.identity.IdentityId
import kotlinx.coroutines.launch

/**
 * Settings → Identities. Lists every identity on the device, marks
 * the active one, lets the user add a fresh identity ("Add" FAB) or
 * remove an existing one (per-row trash → name-confirm dialog).
 *
 * Removing an identity cascades a delete of its local chats via
 * `IdentityRepository.registerRemovalListener` (PR-3 wiring) — the
 * confirm dialog spells that out.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(
    viewModel: IdentitiesViewModel,
    onBack: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    var pendingRemoval by remember { mutableStateOf<IdentitiesViewModel.Row?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identities") },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable(onClick = onBack)
                            .testTag("identities.back"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add identity") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { viewModel.add() },
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                modifier = Modifier.testTag("identities.add"),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(items, key = { it.summary.id.value }) { row ->
                IdentityRow(
                    row = row,
                    onTap = { if (!row.isActive) viewModel.select(row.summary.id) },
                    onRemove = { pendingRemoval = row },
                )
            }
        }
    }

    pendingRemoval?.let { row ->
        RemoveIdentityDialog(
            row = row,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                viewModel.remove(row.summary.id)
                pendingRemoval = null
            },
        )
    }
}

@Composable
private fun IdentityRow(
    row: IdentitiesViewModel.Row,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("identities.row.${row.summary.id.value}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Active checkmark or empty slot.
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (row.isActive) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.summary.name.ifBlank { "Unnamed identity" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isActive) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = "BLS ${row.summary.blsPublicKey.toHexShort()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onRemove)
                .testTag("identities.row.${row.summary.id.value}.remove"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoveIdentityDialog(
    row: IdentitiesViewModel.Row,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val expectedName = row.summary.name.ifBlank { "Unnamed identity" }
    var typed by remember { mutableStateOf("") }
    val canConfirm = typed.trim() == expectedName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${row.summary.name.ifBlank { "this identity" }}?") },
        text = {
            Column {
                Text(
                    "Removing this identity wipes its local chats and messages permanently. " +
                        "Anchored groups stay on chain but you'll lose your local copy.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    "Type \"$expectedName\" to confirm:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(4.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identities.remove.confirm.input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("identities.remove.confirm"),
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun ByteArray.toHexShort(): String {
    if (isEmpty()) return ""
    val sb = StringBuilder()
    for (i in 0 until minOf(4, size)) sb.append("%02x".format(this[i].toInt() and 0xFF))
    if (size > 4) sb.append("…")
    return sb.toString()
}
