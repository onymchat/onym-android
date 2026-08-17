package app.onym.android.moderation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onym.android.moderation.AuthorityManifest
import app.onym.android.moderation.ViolationClass

/**
 * Structured rendering of an authority manifest for the consent
 * surface — the Android port of iOS `ModerationConsentView`'s class
 * cards + procedure card + "what you're signing": every mandatory
 * term visible as labeled rows, never a raw JSON dump.
 *
 * Display only: what the mandate pins stays the retained EXACT bytes
 * (the controller's reviewed snapshot); [manifestHash] is the hash of
 * those bytes, shown so the user can see what their signature binds.
 * Fields this decoding doesn't know are still consented to — they are
 * in the hashed bytes — which is exactly why the hash is on screen.
 */
@Composable
fun ModerationTermsDisplay(
    manifest: AuthorityManifest,
    manifestHash: String,
    modifier: Modifier = Modifier,
    /** In-app markdown viewer for definition links; null falls back
     *  to the system browser. */
    documents: app.onym.android.moderation.PolicyDocumentFetcher? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        manifest.violationClasses.forEach { violationClass ->
            TermSectionLabel(
                app.onym.android.moderation.violationClassDisplayName(violationClass.classId),
            )
            ViolationClassCard(violationClass, documents)
        }

        TermSectionLabel(stringResource(R.string.moderation_terms_procedure).uppercase())
        TermCard {
            manifest.appellate?.takeIf { it.isNotBlank() }?.let {
                TermRow(stringResource(R.string.moderation_term_appellate), it)
            }
            manifest.newHolderAppeal?.takeIf { it.isNotBlank() }?.let {
                TermRow(stringResource(R.string.moderation_term_new_holder), it)
            }
            TermRow(
                stringResource(R.string.moderation_term_valid_until),
                manifest.validUntil.substringBefore('T'),
            )
            TermRow(
                stringResource(R.string.moderation_term_operator),
                manifest.operatorKey,
                monospaceValue = true,
            )
        }

        TermSectionLabel(stringResource(R.string.moderation_terms_signing).uppercase())
        TermCard {
            TermRow(
                stringResource(R.string.moderation_term_manifest_hash),
                manifestHash,
                monospaceValue = true,
            )
        }
        Text(
            text = stringResource(R.string.moderation_terms_hash_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One violation class with all five mandatory terms visible. */
@Composable
private fun ViolationClassCard(
    violationClass: ViolationClass,
    documents: app.onym.android.moderation.PolicyDocumentFetcher?,
) {
    TermCard {
        violationClass.responseWindow.takeIf { it.isNotBlank() }?.let {
            TermRow(stringResource(R.string.moderation_term_response_window), durationDisplay(it))
        }
        violationClass.decisionDeadline.takeIf { it.isNotBlank() }?.let {
            TermRow(stringResource(R.string.moderation_term_decision_deadline), durationDisplay(it))
        }
        violationClass.banTerm.takeIf { it.isNotBlank() }?.let {
            TermRow(
                stringResource(R.string.moderation_term_ban),
                if (it == "permanent") {
                    stringResource(R.string.moderation_term_ban_permanent)
                } else {
                    durationDisplay(it)
                },
            )
        }
        violationClass.appealWindow.takeIf { it.isNotBlank() }?.let {
            TermRow(stringResource(R.string.moderation_term_appeal_window), durationDisplay(it))
        }
        violationClass.appealEffect.takeIf { it.isNotBlank() }?.let {
            TermRow(
                stringResource(R.string.moderation_term_appeal_effect),
                when (it) {
                    "suspensive" -> stringResource(R.string.moderation_term_appeal_suspensive)
                    "non-suspensive" ->
                        stringResource(R.string.moderation_term_appeal_nonsuspensive)
                    // An unknown effect is still a consented term:
                    // show the raw value, never hide it.
                    else -> it
                },
            )
        }
        violationClass.lawfulReporting?.takeIf { it.isNotBlank() }?.let {
            TermRow(stringResource(R.string.moderation_term_lawful_reporting), it)
        }
        violationClass.definition.takeIf { it.isNotBlank() }?.let {
            DefinitionRow(it, documents)
        }
    }
}

/** Definitions are content-addressed; when the address is https, make
 * it tappable so the exact wording is one tap away — rendered in-app
 * as markdown ([MarkdownDocumentDialog]) when a fetcher is wired,
 * else in the browser. */
@Composable
private fun DefinitionRow(
    address: String,
    documents: app.onym.android.moderation.PolicyDocumentFetcher?,
) {
    val uriHandler = LocalUriHandler.current
    val isLink = address.startsWith("https://")
    var viewing by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (viewing && documents != null) {
        MarkdownDocumentDialog(
            title = MarkdownBlocks.impliedTitle(address),
            url = address,
            documents = documents,
            onDismiss = { viewing = false },
        )
    }
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = stringResource(R.string.moderation_term_definition),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = address,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isLink) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = when {
                isLink && documents != null ->
                    Modifier
                        .clickable { viewing = true }
                        .testTag("moderation.consent.definition")
                isLink -> Modifier.clickable { runCatching { uriHandler.openUri(address) } }
                else -> Modifier
            },
        )
    }
}

@Composable
private fun TermSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun TermCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun TermRow(label: String, value: String, monospaceValue: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospaceValue) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `P3D` → "3 days" (localized plural); anything else renders raw —
 * an unparseable term is still a consented term. */
@Composable
private fun durationDisplay(raw: String): String {
    val days = Regex("^P(\\d+)D$").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: return raw
    return pluralStringResource(R.plurals.moderation_term_days, days, days)
}
