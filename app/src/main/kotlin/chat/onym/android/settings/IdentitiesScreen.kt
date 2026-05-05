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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.onym.android.R
import chat.onym.android.group.OnymMark
import chat.onym.android.identity.IdentitiesViewModel
import chat.onym.android.identity.IdentityId

/**
 * Settings → Identities. Lists every identity on the device, marks
 * the active one. Tap a row to drill into [IdentityDetailScreen]
 * (set-active, copy keys, delete). Add via the bottom "Add Identity"
 * button — the redesign moves the FAB into a card-style action so it
 * sits with the list rather than floating over it.
 *
 * Removal moved to the per-identity detail page; this screen is now
 * pure listing + add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(
    viewModel: IdentitiesViewModel,
    onBack: () -> Unit,
    onIdentityClick: (IdentityId) -> Unit,
    onRestoreClick: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val errorMessage = error?.let { e ->
        val keyId = when (e) {
            is IdentitiesViewModel.Error.Switch -> R.string.identities_error_switch
            is IdentitiesViewModel.Error.Add -> R.string.identities_error_add
            is IdentitiesViewModel.Error.Remove -> R.string.identities_error_remove
            is IdentitiesViewModel.Error.Rename -> R.string.identities_error_rename
        }
        stringResource(keyId, e.cause)
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.identities_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("identities.back")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().testTag("identities.list"),
        ) {
            item {
                SettingsFootnote(stringResource(R.string.identities_screen_intro))
            }
            item { SettingsSectionLabel(stringResource(R.string.identities_section_your)) }
            item {
                SettingsCard {
                    items.forEachIndexed { i, row ->
                        IdentityListRow(
                            row = row,
                            isLast = i == items.size - 1,
                            onClick = { onIdentityClick(row.summary.id) },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                SettingsCard(modifier = Modifier.testTag("identities.add_card")) {
                    IdentityActionRow(
                        icon = Icons.Filled.Add,
                        background = SettingsTile.Blue,
                        label = stringResource(R.string.identities_add_button),
                        onClick = { viewModel.add() },
                        rowTestTag = "identities.add",
                    )
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                SettingsCard(modifier = Modifier.testTag("identities.restore_card")) {
                    IdentityActionRow(
                        icon = Icons.Filled.Restore,
                        background = SettingsTile.Green,
                        label = stringResource(R.string.identities_restore_button),
                        onClick = onRestoreClick,
                        rowTestTag = "identities.restore",
                    )
                }
            }
            item {
                SettingsFootnote(stringResource(R.string.identities_screen_footer))
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun IdentityActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    label: String,
    onClick: () -> Unit,
    rowTestTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .testTag(rowTestTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = background,
        )
    }
}

@Composable
private fun IdentityListRow(
    row: IdentitiesViewModel.Row,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val displayName = row.summary.name.ifBlank { stringResource(R.string.identity_unnamed) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .testTag("identities.row.${row.summary.id.value}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Identity tile — OnymMark inside a soft circle. Active gets a blue ring.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (row.isActive) Color(0xFFE0EEFE) else Color(0xFFEAEAEE)),
                contentAlignment = Alignment.Center,
            ) {
                OnymMark(
                    size = 22.dp,
                    color = if (row.isActive) SettingsTile.Blue else SettingsTile.Gray,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (row.isActive) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = heroHex(row.summary.blsPublicKey),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.isActive) {
                Text(
                    text = stringResource(R.string.identity_detail_active_marker),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = SettingsTile.Green,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SettingsTile.Green.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (!isLast) SettingsHairline(insetStart = 68.dp)
    }
}
