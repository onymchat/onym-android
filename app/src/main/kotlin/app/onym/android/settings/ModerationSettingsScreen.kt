package app.onym.android.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.design.SettingsCard
import app.onym.android.design.SettingsFootnote
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsHairline
import app.onym.android.design.SettingsSectionLabel
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.moderation.AuthorityManifest
import app.onym.android.moderation.MandateRecord
import app.onym.android.moderation.ModerationJson
import app.onym.android.moderation.ModerationRepository
import app.onym.android.strings.R
import java.util.Base64
import kotlinx.coroutines.launch

/**
 * Settings → Moderation — the Android port of iOS
 * `ModerationSettingsView`: the consented authority and its pinned
 * terms, the registration state, the mandate history, and the
 * "Switch authority" path (a fresh consent — the old mandate stays
 * exactly as signed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationSettingsScreen(
    repository: ModerationRepository,
    /** Retry delivery of the persisted signed mandate (no new
     *  consent); answers a human-readable failure or null. */
    onRetryRegistration: suspend () -> String?,
    /** Launch the switch/first-choice consent surface (a fresh
     *  one-snapshot review). */
    onSwitchAuthority: () -> Unit,
    /** Show the consented terms rendered from the retained exact
     *  bytes (the same structured display consent used). */
    onViewTerms: (MandateRecord) -> Unit,
    onBack: () -> Unit,
) {
    val snapshots by repository.snapshots.collectAsState()
    // Per-identity resolution is suspend (it reads the signer's
    // current key) — recompute whenever the record set changes.
    val active by produceState<MandateRecord?>(initialValue = null, snapshots) {
        value = runCatching { repository.activeMandateRecord() }.getOrNull()
    }
    val pending by produceState<MandateRecord?>(initialValue = null, snapshots) {
        value = runCatching { repository.pendingRegistration() }.getOrNull()
    }
    val previous = snapshots.records.filter { !it.isActive }
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    var retryError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.moderation_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("moderation.settings"),
        ) {
            item { SettingsSectionLabel(stringResource(R.string.moderation_settings_current_authority)) }
            val record = active
            if (record != null) {
                item {
                    SettingsCard {
                        ActiveMandateDetails(record)
                        SettingsHairline()
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.Description, SettingsTile.Gray)
                            },
                            title = stringResource(R.string.moderation_settings_view_terms),
                            showChevron = true,
                            onClick = { onViewTerms(record) },
                            modifier = Modifier.testTag("moderation.settings.terms"),
                        )
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.Autorenew, SettingsTile.Purple)
                            },
                            title = stringResource(R.string.moderation_settings_switch_title),
                            subtitle = stringResource(R.string.moderation_settings_switch_subtitle),
                            showChevron = true,
                            onClick = onSwitchAuthority,
                            isLast = true,
                            modifier = Modifier.testTag("moderation.settings.switch"),
                        )
                    }
                }
                item { SettingsFootnote(stringResource(R.string.moderation_settings_footnote)) }
            } else {
                item {
                    SettingsCard {
                        SettingsRow(
                            leading = {
                                SettingsTileBox(Icons.Filled.GppGood, SettingsTile.Purple)
                            },
                            title = stringResource(R.string.moderation_settings_choose_title),
                            subtitle = stringResource(R.string.moderation_settings_choose_subtitle),
                            showChevron = true,
                            onClick = onSwitchAuthority,
                            isLast = true,
                            modifier = Modifier.testTag("moderation.settings.choose"),
                        )
                    }
                }
            }

            // Registration state of the ACTIVE mandate: delivered, or
            // owed with a retry that redelivers the exact persisted
            // artifact — no new consent is created.
            if (record != null) {
                item { SettingsSectionLabel(stringResource(R.string.moderation_settings_registration)) }
                item {
                    SettingsCard {
                        if (pending == null) {
                            SettingsRow(
                                leading = {
                                    SettingsTileBox(Icons.Filled.GppGood, SettingsTile.Green)
                                },
                                title = stringResource(R.string.moderation_settings_registered),
                                showChevron = false,
                                isLast = true,
                            )
                        } else {
                            SettingsRow(
                                leading = {
                                    SettingsTileBox(Icons.Filled.Autorenew, SettingsTile.Amber)
                                },
                                title = stringResource(R.string.moderation_settings_registration_retry),
                                subtitle = if (retrying) {
                                    stringResource(R.string.moderation_settings_registration_retrying)
                                } else {
                                    stringResource(R.string.moderation_settings_registration_pending)
                                },
                                showChevron = false,
                                onClick = {
                                    if (!retrying) {
                                        retrying = true
                                        retryError = null
                                        scope.launch {
                                            retryError = onRetryRegistration()
                                            retrying = false
                                        }
                                    }
                                },
                                isLast = true,
                                modifier = Modifier.testTag("moderation.settings.retryRegistration"),
                            )
                        }
                    }
                }
                retryError?.let { message ->
                    item { SettingsFootnote(message) }
                }
            }

            if (previous.isNotEmpty()) {
                item { SettingsSectionLabel(stringResource(R.string.moderation_settings_previous)) }
                item {
                    SettingsCard {
                        previous.forEachIndexed { index, past ->
                            SettingsRow(
                                leading = {
                                    SettingsTileBox(Icons.Filled.Description, SettingsTile.Gray)
                                },
                                title = past.authorityName,
                                subtitle = stringResource(
                                    R.string.moderation_settings_previous_row,
                                    past.mandate.acceptedAt.substringBefore('T'),
                                    past.mandate.manifestHash.take(16),
                                ),
                                showChevron = false,
                                isLast = index == previous.lastIndex,
                            )
                        }
                    }
                }
                item {
                    SettingsFootnote(
                        stringResource(R.string.moderation_settings_previous_footnote),
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** The iOS `activeCard` rows: every fact the consent pinned, from the
 * persisted record — never a refetch. */
@Composable
private fun ActiveMandateDetails(record: MandateRecord) {
    val manifest = remember(record) { record.decodedManifest() }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        DetailRow(
            stringResource(R.string.moderation_settings_authority),
            record.authorityName,
        )
        DetailRow(
            stringResource(R.string.moderation_settings_component),
            record.mandate.authority,
            monospace = true,
        )
        DetailRow(
            stringResource(R.string.moderation_settings_consented),
            record.mandate.acceptedAt.substringBefore('T'),
        )
        DetailRow(
            stringResource(R.string.moderation_settings_manifest_hash),
            record.mandate.manifestHash,
            monospace = true,
        )
        if (manifest != null) {
            DetailRow(
                stringResource(R.string.moderation_settings_valid_until),
                manifest.validUntil.substringBefore('T'),
            )
            DetailRow(
                stringResource(R.string.moderation_settings_classes),
                manifest.violationClasses.joinToString(", ") { it.classId },
                last = true,
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    last: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = if (last) 0.dp else 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Decode the consented manifest from the retained exact bytes; null
 * renders the record's own facts only (never a refetch). */
internal fun MandateRecord.decodedManifest(): AuthorityManifest? = runCatching {
    ModerationJson.json.decodeFromString(
        AuthorityManifest.serializer(),
        Base64.getDecoder().decode(manifestBytesBase64).decodeToString(),
    )
}.getOrNull()

/**
 * The consented terms, rendered from the RETAINED exact bytes with
 * the same structured display consent used — never a refetch, so what
 * is shown is what the mandate pins even if the hosted manifest has
 * since changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentedTermsScreen(
    repository: ModerationRepository,
    onBack: () -> Unit,
) {
    val snapshots by repository.snapshots.collectAsState()
    val record by produceState<MandateRecord?>(initialValue = null, snapshots) {
        value = runCatching { repository.activeMandateRecord() }.getOrNull()
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.moderation_settings_view_terms)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        val current = record
        val manifest = current?.decodedManifest()
        if (current != null && manifest != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .testTag("moderation.settings.termsView"),
            ) {
                app.onym.android.moderation.ui.ModerationTermsDisplay(
                    manifest = manifest,
                    manifestHash = current.mandate.manifestHash,
                )
            }
        }
    }
}
