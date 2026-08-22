package app.onym.android.chats

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.onym.android.design.LocalOnymTokens
import app.onym.android.group.GroupRulesProof
import app.onym.android.group.GroupRulesStanding
import app.onym.android.strings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * One member's standing on the group's rules, and the file that can
 * carry it off the device.
 *
 * The interesting question about an agreement is usually asked
 * somewhere else — to a moderator, to a committee, to someone deciding
 * whether a person can be held to something — so the whole sheet is
 * arranged around the export: what is being claimed, the bytes that
 * back it, and a button that hands both over in a form nobody has to
 * take on trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberRulesProofSheet(
    proof: GroupRulesProof,
    onDismiss: () -> Unit,
) {
    val tokens = LocalOnymTokens.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var exported by remember { mutableStateOf<File?>(null) }
    var writeFailed by remember { mutableStateOf(false) }

    // Written on appear rather than on tap: a share sheet that has to
    // wait for a file write is a share sheet that opens empty. Keyed on
    // the proof, so a rules change under an open sheet rewrites it —
    // the screen re-renders from the live group, and `exported` holding
    // the previous proof's file would export the old bytes.
    LaunchedEffect(proof) {
        exported = null
        writeFailed = false
        if (!proof.standing.hasSomethingToShow) return@LaunchedEffect
        exported = withContext(Dispatchers.IO) { writeExport(context, proof) }
        writeFailed = exported == null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .testTag("rules_proof.sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // `of` returns null for every standing without a mark and
            // the caller dismisses on null, so there is always one
            // here. Asserted rather than branched: a fallback for a
            // state that cannot arise is a fallback nobody maintains,
            // and it was costing translators a string that could never
            // be shown.
            val mark = requireNotNull(groupRulesMark(proof.standing))
            Icon(mark.icon, contentDescription = null, tint = mark.color, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = mark.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.text,
                modifier = Modifier.testTag("rules_proof.standing"),
            )
            groupRulesExplanation(proof.standing)?.let {
                Spacer(Modifier.height(6.dp))
                Text(text = it, fontSize = 13.sp, color = tokens.text2)
            }

            proof.rules?.let { rules ->
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.surface2)
                        .padding(14.dp)
                        .testTag("rules_proof.rules"),
                ) {
                    Text(
                        // The author's own words aren't something they
                        // signed, and heading them "what they signed"
                        // directly under "founders don't sign their
                        // own" had the screen contradicting itself.
                        text = if (proof.standing == GroupRulesStanding.AUTHOR) {
                            stringResource(R.string.rules_proof_rules_they_set)
                        } else {
                            stringResource(R.string.rules_proof_what_signed)
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.text3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(text = rules, fontSize = 14.sp, color = tokens.text)
                    // Read from the standing rather than by comparing
                    // the two strings: `GroupRulesProof` single-sources
                    // this same fact for the file, and two derivations
                    // agree only until normalization changes.
                    if (proof.standing == GroupRulesStanding.SIGNED_EARLIER_VERSION) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.rules_proof_changed_since),
                            fontSize = 12.sp,
                            color = tokens.text2,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.surface)
                    .padding(14.dp)
                    .testTag("rules_proof.bytes"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Always, even where there is no signature: this sheet
                // is titled by an alias the member chose for themselves,
                // and two people can choose the same one. The
                // fingerprint is what says which member this is about.
                ByteRow(stringResource(R.string.rules_proof_member), shortHex(proof.memberBlsHex))
                // Shown whenever the file carries them, which since
                // the export began shipping unverified bytes is not the
                // same as "proven". Gating on `isProven` hid the
                // signature *and* claimed the file had none, over a
                // document containing it and a note inviting the reader
                // to re-check — in the one standing that should give
                // someone pause.
                if (proof.signature != null) {
                    ByteRow(
                        stringResource(R.string.rules_proof_signing_key),
                        shortHex(proof.sendingPublicKey?.toHexLowercase().orEmpty()),
                    )
                    ByteRow(
                        stringResource(R.string.rules_proof_signature),
                        shortHex(proof.signature?.toHexLowercase().orEmpty()),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            val file = exported
            Button(
                onClick = { file?.let { share(context, it) } },
                // Disabled rather than absent while the write is in
                // flight: it is the primary action, and a thumb is
                // already moving toward it when the layout would
                // otherwise jump.
                enabled = file != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rules_proof.export"),
            ) {
                Text(stringResource(R.string.rules_proof_export))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    writeFailed -> stringResource(R.string.rules_proof_write_failed)
                    // Keyed on what the file contains, not on the
                    // verdict: "there is no signature in it to check"
                    // is a claim about the artifact, and it was false
                    // wherever a stored signature failed to verify.
                    proof.signature != null ->
                        stringResource(R.string.rules_proof_export_note_signed)
                    else -> stringResource(R.string.rules_proof_export_note_unproven)
                },
                fontSize = 11.sp,
                color = if (writeFailed) tokens.red else tokens.text2,
            )
        }
    }
}

@Composable
private fun ByteRow(label: String, value: String) {
    val tokens = LocalOnymTokens.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tokens.text3)
        Spacer(Modifier.weight(1f))
        Text(text = value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = tokens.text2)
    }
}

/**
 * Writes the export into a directory of its own.
 *
 * Its own directory per write, because the filename is stable for a
 * member — group, alias, key — so two writes for one sheet would
 * otherwise contend for a path: a superseded write could leave stale
 * bytes under a URL the fresh one had already published, and cleaning
 * up "the previous file" would delete the live one.
 */
private fun writeExport(context: Context, proof: GroupRulesProof): File? = runCatching {
    val root = File(context.cacheDir, EXPORT_ROOT).apply { mkdirs() }
    val directory = File(root, "$EXPORT_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
    File(directory, proof.suggestedFileName).apply { writeText(proof.json()) }
}.getOrNull()

internal fun sweepStaleRulesProofExports(context: Context) {
    val root = File(context.cacheDir, EXPORT_ROOT)
    val cutoff = System.currentTimeMillis() - STALE_EXPORT_AGE_MS
    root.listFiles().orEmpty()
        .filter { it.name.startsWith(EXPORT_PREFIX) && it.lastModified() < cutoff }
        .forEach { it.deleteRecursively() }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.rulesproof", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.rules_proof_export)),
    )
}

private fun shortHex(hex: String): String =
    if (hex.length <= 16) hex else "${hex.take(8)}…${hex.takeLast(8)}"

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}

private const val EXPORT_ROOT = "rules-proof"
private const val EXPORT_PREFIX = "rules-proof-"
private const val STALE_EXPORT_AGE_MS = 60L * 60L * 1000L
