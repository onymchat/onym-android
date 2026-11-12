package app.onym.android.moderation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EscalatorWarning
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.onym.android.design.SettingsRow
import app.onym.android.design.SettingsTile
import app.onym.android.design.SettingsTileBox
import app.onym.android.design.SymbolHint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.onym.android.moderation.MandateRecord
import kotlinx.coroutines.launch

/**
 * The consent surface: the reviewed manifest snapshot rendered from
 * its exact retained bytes, an Agree that consents with exactly that
 * snapshot, and the Unavailable path (nothing to consent to — the
 * onboarding step reports `Unavailable`, never a silent skip).
 *
 * Hosted in two places with the same body: the onboarding Moderation
 * step, and the post-onboarding full-screen `NeedsConsent` /
 * re-consent gate.
 */
@Composable
fun ModerationConsentContent(
    controller: ModerationConsentController,
    onConsented: (MandateRecord) -> Unit,
    onUnavailableContinue: (() -> Unit)?,
    /**
     * True when this surface IS the screen (RootScreen's NeedsConsent
     * host): it wraps itself in a Surface and its own vertical scroll
     * so Agree stays reachable on small viewports / large fonts.
     * False when a scrolling host (the onboarding step scaffold)
     * already provides both — nesting an unbounded scroll inside a
     * scroll does not measure.
     */
    standalone: Boolean = true,
    /**
     * DEBUG-BUILD escape hatch: renders a "Skip (debug build)" action
     * on the Review surface that takes the same deferral path as
     * Unavailable's Continue. A sideloaded debug/emulator build can
     * NEVER pass the backend's classifier (not Play-installed, debug
     * signing key), so its deterministic refusal — correctly
     * retry-only for release users — would otherwise hard-block every
     * local run at this step. This module has no build-type knowledge
     * (same posture as OkHttpEnforcementBackendClient's loopback
     * carve-out): the app passes BuildConfig.DEBUG, so a release
     * binary never renders the affordance, and it is inert anyway
     * wherever the host offers no deferral (onUnavailableContinue
     * null — the enrollment-lost re-consent gate).
     */
    debugSkipAllowed: Boolean = false,
    /**
     * The host signs with its own button — the onboarding step's primary
     * calls [ModerationConsentController.agree] — so the review drops its
     * Agree button and offers re-choosing the authority instead (iOS
     * `hostOwnsSigning` parity).
     */
    hostOwnsSigning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by controller.snapshots.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(controller) {
        if (state is ModerationConsentController.UiState.Loading) controller.load()
    }

    // The caller's follow-up rides on the STATE, not on the click's
    // coroutine: agree() is NonCancellable, so a surface torn down
    // mid-transaction (rotation, a gate flip) still lands Consented —
    // and the recreated surface (whose controller re-emits Consented
    // from the persisted record) fires the follow-up here instead of
    // losing it with the cancelled click handler.
    LaunchedEffect(state) {
        (state as? ModerationConsentController.UiState.Consented)
            ?.let { onConsented(it.record) }
    }

    var showsAuthorities by remember { mutableStateOf(false) }
    var showsTermsText by remember { mutableStateOf(false) }

    // Textless, like iOS: every control an icon with its old wording as
    // its accessibility label; the terms' class symbols name themselves on
    // tap, and the full terms in words are one tap away.
    val body: @Composable (Modifier) -> Unit = { columnModifier ->
        Column(
            modifier = columnModifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("moderation.consent"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (standalone) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = stringResource(R.string.moderation_consent_pick_title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            }
            when (val current = state) {
                is ModerationConsentController.UiState.Loading,
                is ModerationConsentController.UiState.Consenting,
                -> {
                    val loading = stringResource(R.string.moderation_consent_loading)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
                }

                is ModerationConsentController.UiState.Unavailable -> {
                    val message = stringResource(R.string.moderation_consent_unavailable)
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = message,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RoundIcon(
                            icon = Icons.Filled.Refresh,
                            label = stringResource(R.string.moderation_consent_retry),
                            onClick = { scope.launch { controller.load() } },
                            modifier = Modifier.testTag("moderation.consent.retry"),
                        )
                        if (onUnavailableContinue != null) {
                            RoundIcon(
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                label = stringResource(R.string.moderation_consent_continue),
                                onClick = onUnavailableContinue,
                                filled = true,
                                modifier = Modifier.testTag("moderation.consent.continue"),
                            )
                        }
                    }
                }

                is ModerationConsentController.UiState.Picking -> {
                    // The authorities sit behind one configure icon — the
                    // same sliders the welcome screen uses for setup.
                    RoundIcon(
                        icon = Icons.Filled.Tune,
                        label = stringResource(R.string.moderation_consent_pick_title),
                        onClick = { showsAuthorities = true },
                        size = 64.dp,
                        modifier = Modifier.testTag("moderation.consent.choose"),
                    )
                    current.error?.let { ErrorSymbol(it) }
                    if (showsAuthorities) {
                        AuthorityListSheet(
                            listings = current.listings,
                            onPick = { listing ->
                                showsAuthorities = false
                                scope.launch { controller.select(listing) }
                            },
                            onDismiss = { showsAuthorities = false },
                        )
                    }
                }

                is ModerationConsentController.UiState.Review -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.semantics(mergeDescendants = false) {
                            contentDescription = current.listing.name
                        },
                    ) {
                        current.manifest.violationClasses.forEach { violationClass ->
                            SymbolHint(
                                icon = violationClassIcon(violationClass.classId),
                                label = violationClassName(violationClass.classId),
                                size = 34.dp,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    RoundIcon(
                        icon = Icons.Filled.Description,
                        label = stringResource(R.string.moderation_consent_read_terms),
                        onClick = { showsTermsText = true },
                        size = 64.dp,
                        modifier = Modifier.testTag("moderation.consent.terms_text"),
                    )
                    current.error?.let { ErrorSymbol(it) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current.canPickAnother) {
                            RoundIcon(
                                icon = Icons.Filled.Tune,
                                label = stringResource(R.string.moderation_consent_pick_another),
                                onClick = { scope.launch { controller.backToAuthorities() } },
                                modifier = Modifier.testTag("moderation.consent.pickAnother"),
                            )
                        }
                        if (!hostOwnsSigning) {
                            val agree = stringResource(R.string.moderation_consent_agree)
                            Button(
                                onClick = { scope.launch { controller.agree() } },
                                modifier = Modifier
                                    .height(52.dp)
                                    .widthIn(min = 160.dp)
                                    .semantics { contentDescription = agree }
                                    .testTag("moderation.consent.agree"),
                            ) { Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(26.dp)) }
                        }
                    }
                    if (debugSkipAllowed && onUnavailableContinue != null) {
                        RoundIcon(
                            icon = Icons.Filled.BugReport,
                            label = stringResource(R.string.moderation_consent_debug_skip),
                            onClick = onUnavailableContinue,
                            modifier = Modifier.testTag("moderation.consent.debugSkip"),
                        )
                    }
                    if (showsTermsText) {
                        TermsTextSheet(
                            authorityName = current.listing.name,
                            onDismiss = { showsTermsText = false },
                        ) {
                            ModerationTermsDisplay(
                                manifest = current.manifest,
                                manifestHash = current.manifestHash,
                                modifier = Modifier.testTag("moderation.consent.terms"),
                                documents = controller.documents,
                            )
                        }
                    }
                }

                is ModerationConsentController.UiState.Consented -> {
                    // Terminal; the host navigated on [onConsented].
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = stringResource(R.string.moderation_consent_done),
                        tint = androidx.compose.ui.graphics.Color(0xFF30B45A),
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("moderation.consent.done"),
                    )
                }
            }
        }
    }

    if (standalone) {
        androidx.compose.material3.Surface(modifier = modifier.fillMaxSize()) {
            body(
                Modifier
                    .fillMaxSize()
                    // Standalone = full-screen NeedsConsent host on
                    // RootScreen's early-return path — the safe area
                    // is this surface's own job (the embedded mode
                    // inherits OnboardingScaffold's insets instead).
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState()),
            )
        }
    } else {
        body(modifier.fillMaxWidth())
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    filled: Boolean = false,
) {
    if (filled) {
        FilledIconButton(onClick = onClick, modifier = modifier.size(size)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.42f))
        }
    } else {
        FilledTonalIconButton(onClick = onClick, modifier = modifier.size(size)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** A failure the controller reports: a warning glyph, the message on its
 *  accessibility label. */
@Composable
private fun ErrorSymbol(message: String) {
    Icon(
        Icons.Filled.Warning,
        contentDescription = message,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .size(26.dp)
            .testTag("moderation.consent.error"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorityListSheet(
    listings: List<app.onym.android.moderation.AuthorityListing>,
    onPick: (app.onym.android.moderation.AuthorityListing) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            listings.forEach { listing ->
                // The name is data — the one word the pick needs.
                SettingsRow(
                    leading = { SettingsTileBox(Icons.Filled.Shield, SettingsTile.Indigo) },
                    title = listing.name,
                    onClick = { onPick(listing) },
                    modifier = Modifier.testTag("moderation.consent.pick.${listing.componentId}"),
                )
            }
        }
    }
}

/** The terms in words — what the review's document icon opens: every
 *  class with all its terms, the procedure, and the hash being signed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermsTextSheet(
    authorityName: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.moderation_consent_title, authorityName),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("moderation.consent.terms_text.close"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.moderation_consent_close))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.moderation_consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** A violation class as a symbol — child safety for CSAM, and so on. */
private fun violationClassIcon(classId: String): ImageVector = when (classId) {
    "csam" -> Icons.Filled.EscalatorWarning
    "unsolicited-pornography" -> Icons.Filled.VisibilityOff
    "spam" -> Icons.Filled.MarkEmailUnread
    else -> Icons.Filled.GppMaybe
}

@Composable
private fun violationClassName(classId: String): String = when (classId) {
    "csam" -> stringResource(R.string.moderation_class_csam)
    "unsolicited-pornography" -> stringResource(R.string.moderation_class_unsolicited_pornography)
    "spam" -> stringResource(R.string.moderation_class_spam)
    else -> classId.replace('-', ' ')
}
