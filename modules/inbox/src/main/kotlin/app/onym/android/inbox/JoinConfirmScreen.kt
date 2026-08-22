package app.onym.android.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.onym.android.strings.R

/**
 * The screen between an invitation and a join request.
 *
 * It exists because arriving is not consenting. A link reaches this app
 * through an exported activity — another app on the device can start it,
 * and so can a browsable link — and the request it would send carries
 * this identity's name and its long-term public keys to whoever holds
 * the invite key. Acting on delivery alone let anything that could form
 * a URL disclose all of that silently.
 *
 * It also earns its place for the person who *did* tap the link: the
 * name they arrive under is the name the founder decides on, and until
 * now they had no chance to set it.
 *
 * Mirrors `JoinConfirmView` in onym-ios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinConfirmScreen(
    confirmation: PendingChatsViewModel.JoinConfirmation,
    /** Called with the typed name. The caller owns the send. */
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var label by rememberSaveable(confirmation.rowId) {
        mutableStateOf(confirmation.suggestedLabel)
    }
    var isSending by remember { mutableStateOf(false) }
    // Unticked until the person ticks it. Only ever gates Send for a
    // group that has rules — see below.
    // Keyed on the text as well as the row: the row id is
    // "<group>:<owner>" and survives a founder rewriting the rules, so a
    // tick restored across that change would enable Send over words
    // nobody read.
    var agreedToRules by rememberSaveable(confirmation.rowId, confirmation.rules) {
        mutableStateOf(false)
    }
    // A group that asks nothing of its joiners keeps the one-tap join
    // the pre-filled name bought: there is nothing to affirm, and a tick
    // standing for nothing is friction that teaches people to tick
    // without reading.
    val canSend = !isSending && label.isNotBlank() &&
        (confirmation.rules == null || agreedToRules)
    val title = confirmation.groupName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.pending_chat_unnamed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_confirm_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("join_confirm.cancel"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val alias = confirmation.inviterAlias.trim()
                if (alias.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pending_chat_invited_you, alias),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            confirmation.invitationMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_confirm.invitation_message"),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            confirmation.rules?.let { rules ->
                Spacer(Modifier.height(16.dp))
                RulesCard(
                    rules = rules,
                    agreed = agreedToRules,
                    onAgreedChange = { agreedToRules = it },
                )
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                singleLine = true,
                label = { Text(stringResource(R.string.join_confirm_ask_as)) },
                placeholder = { Text(stringResource(R.string.join_confirm_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("join_confirm.name_field"),
            )
            Spacer(Modifier.height(6.dp))
            // The name rides with this join only. One identity can be
            // "Sam" in a book club and "S." in a tenants' group without
            // either being a second identity.
            Text(
                text = stringResource(R.string.join_confirm_name_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Disclosure(confirmation)

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    isSending = true
                    onSend(label.trim())
                },
                enabled = canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("join_confirm.send"),
            ) {
                Text(
                    when {
                        isSending -> stringResource(R.string.pending_chat_sending)
                        confirmation.rules != null ->
                            stringResource(R.string.join_confirm_agree_and_send)
                        else -> stringResource(R.string.join_confirm_send)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The rules, and the tick that turns reading them into agreeing.
 *
 * Send signs this exact text, so it is shown in full rather than
 * truncated behind a "more": a signature over words that were folded
 * away is not much of an agreement. The text is founder-supplied and
 * untrusted, hence rendered plain — no markdown, no links to follow.
 */
@Composable
private fun RulesCard(
    rules: String,
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("join_confirm.rules"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.join_confirm_rules_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = rules,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("join_confirm.rules_text"),
            )
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = agreed,
                        role = Role.Checkbox,
                        onValueChange = onAgreedChange,
                    )
                    .testTag("join_confirm.agree_toggle"),
            ) {
                Checkbox(checked = agreed, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.join_confirm_agree),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Said plainly, because it is the part that outlives the
            // tap: the founder keeps this, and can show it.
            Text(
                text = stringResource(R.string.join_confirm_signed_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Names what leaves the device and who receives it, in that order, while
 * there is still a way out.
 */
@Composable
private fun Disclosure(confirmation: PendingChatsViewModel.JoinConfirmation) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("join_confirm.disclosure"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.join_confirm_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (confirmation.rules != null) {
                Text(
                    text = stringResource(R.string.join_confirm_rules_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DisclosureRow(
                key = stringResource(R.string.join_confirm_identity_label),
                value = confirmation.identityName,
            )
            DisclosureRow(
                key = stringResource(R.string.join_confirm_group_label),
                value = shortHex(confirmation.groupIdHex),
            )
            DisclosureRow(
                key = stringResource(R.string.join_confirm_invite_key_label),
                value = shortHex(
                    confirmation.introPublicKey.joinToString("") { "%02x".format(it) },
                ),
            )
        }
    }
}

@Composable
private fun DisclosureRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Enough to compare against what the host is showing, short enough to
 *  read out loud. */
private fun shortHex(value: String): String =
    if (value.length <= 12) value else value.take(6) + "…" + value.takeLast(6)
