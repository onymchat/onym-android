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
import androidx.compose.material.icons.filled.Gavel
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
import app.onym.android.moderation.CaseNotice
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
    /** Show the consented terms (the host re-resolves the active
     *  record; the terms screen renders the retained exact bytes). */
    onViewTerms: () -> Unit,
    /** Cases the gate currently reports open against this identity —
     *  the notices ride along on an otherwise-operational gate answer.
     *  Empty is the normal state. */
    openCases: List<CaseNotice> = emptyList(),
    /** Open the appeal surface for one case id. */
    onAppealCase: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val snapshots by repository.snapshots.collectAsState()
    // TRI-STATE per-identity resolution: identityConsentState() is
    // suspend (it reads the signer's current key) and can throw —
    // and until it answers, this screen KNOWS NOTHING. Null here is
    // "unresolved", rendered as nothing: treating it as "no mandate"
    // put a live 'Choose a moderation authority' in front of a user
    // who already has one, and "Delivered" in front of a mandate
    // still pending. The whole read is one call so active/previous/
    // pending can never disagree about whose identity they describe.
    val consent by produceState<ModerationRepository.IdentityConsentState?>(
        initialValue = null,
        snapshots,
    ) {
        value = runCatching { repository.identityConsentState() }.getOrNull()
    }
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
            // Unresolved window: render nothing below the title —
            // never the wrong answer (see the tri-state note above).
            val resolved = consent ?: return@LazyColumn
            item { SettingsSectionLabel(stringResource(R.string.moderation_settings_current_authority)) }
            val record = resolved.active
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
                            onClick = onViewTerms,
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
                        if (!resolved.registrationPending) {
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

            // Cases the gate reported open against this identity. A
            // case-open mark never restricts the app (that would make
            // an accusation a punishment), so this is the only surface
            // that shows one — and the only in-app route to an appeal.
            // Gated on an active mandate: an appeal cites the standing
            // that mandate gives, so without one the button could only
            // fail.
            if (record != null && openCases.isNotEmpty()) {
                item { SettingsSectionLabel(stringResource(R.string.moderation_settings_open_cases)) }
                item {
                    SettingsCard {
                        openCases.forEachIndexed { index, notice ->
                            SettingsRow(
                                leading = {
                                    SettingsTileBox(Icons.Filled.Gavel, SettingsTile.Purple)
                                },
                                title = stringResource(
                                    R.string.moderation_settings_open_case_appeal,
                                ),
                                subtitle = notice.classId.takeIf { it.isNotBlank() }?.let {
                                    stringResource(
                                        R.string.moderation_settings_open_case_class,
                                        it,
                                    )
                                } ?: notice.caseId,
                                showChevron = true,
                                onClick = { onAppealCase(notice.caseId) },
                                isLast = index == openCases.lastIndex,
                                modifier = Modifier.testTag(
                                    "moderation.settings.appeal.${notice.caseId}",
                                ),
                            )
                        }
                    }
                }
                item {
                    SettingsFootnote(
                        stringResource(R.string.moderation_settings_open_cases_footnote),
                    )
                }
            }

            val previous = resolved.previous
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
    /** In-app markdown viewer for definition links — the same
     *  fetcher the consent surface uses. */
    documents: app.onym.android.moderation.PolicyDocumentFetcher?,
    onBack: () -> Unit,
) {
    val snapshots by repository.snapshots.collectAsState()
    // Same tri-state contract as the settings screen: null =
    // unresolved (render nothing yet), resolved-without-active or an
    // undecodable record = the explanatory fallback, never a silent
    // empty body under a title.
    val consent by produceState<ModerationRepository.IdentityConsentState?>(
        initialValue = null,
        snapshots,
    ) {
        value = runCatching { repository.identityConsentState() }.getOrNull()
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
        val resolved = consent ?: return@Scaffold
        val current = resolved.active
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
                    documents = documents,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .testTag("moderation.settings.termsUnavailable"),
            ) {
                Text(
                    text = stringResource(R.string.moderation_settings_terms_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
