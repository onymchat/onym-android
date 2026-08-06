package app.onym.android.group.creategroup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.R
import app.onym.android.group.IntroCapability
import app.onym.android.group.ShareInviteViewModel
import app.onym.android.settings.OnymQrCode

/**
 * Post-create surface. The just-created group is identified by hex
 * [groupId]; the VM resolves it from the repository, mints a fresh
 * deeplink capability, and surfaces the link. The user shares via
 * the system share sheet, copies it, or skips.
 *
 * Loads the link on each entry, idempotently — a QR the user already
 * screenshotted keeps working.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShareInviteScreen(
    groupId: String,
    viewModel: ShareInviteViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val otherInvites by viewModel.otherInvites.collectAsStateWithLifecycle()
    val isRotating by viewModel.isRotating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        viewModel.mintFor(groupId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_invite_title)) },
                actions = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.share_invite_hero_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.share_invite_hero_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            when (val s = state) {
                is ShareInviteViewModel.State.Idle,
                is ShareInviteViewModel.State.Minting -> {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp).testTag("share_invite.minting"),
                        )
                    }
                }

                is ShareInviteViewModel.State.Ready -> {
                    val chooserTitle = stringResource(R.string.share_invite_link_chooser)
                    val clipboardLabel = stringResource(R.string.share_invite_clipboard_label)
                    // Same brand-anchored QR component the identity
                    // invite-key screen uses. Encodes the *exact* link
                    // string the Share / Copy buttons hand out, so a
                    // scanned QR and a tapped link decode identically.
                    OnymQrCode(
                        value = s.link,
                        size = 220.dp,
                        modifier = Modifier.testTag("share_invite.qr"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.share_invite_qr_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                // Share the plain link only — no group-name-
                                // decorated text — so recipients get a single
                                // clean invite URL (matches iOS).
                                putExtra(Intent.EXTRA_TEXT, s.link)
                                type = "text/plain"
                            }
                            context.startActivity(
                                Intent.createChooser(sendIntent, chooserTitle),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_invite.share_button"),
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.share_invite_link_chooser))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, s.link))
                            copied = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_invite.copy_button")
                            // Expose the raw invite link to UI tests via
                            // semantics so they can read it without a
                            // clipboard round-trip.
                            .semantics { contentDescription = s.link },
                    ) {
                        Text(
                            stringResource(
                                if (copied) R.string.share_invite_copied
                                else R.string.share_invite_copy,
                            ),
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    // The only way a leaked link stops working, framed as
                    // generate-a-new-one so a working link always remains.
                    TextButton(
                        onClick = {
                            copied = false
                            viewModel.rotateLink(groupId)
                        },
                        enabled = !isRotating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_invite.rotate_button"),
                    ) {
                        Text(
                            stringResource(
                                if (isRotating) R.string.share_invite_rotating
                                else R.string.share_invite_rotate,
                            ),
                        )
                    }
                    Text(
                        text = stringResource(R.string.share_invite_rotate_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // The create-time offers, one per invitee — this list
                    // is the only way they are retired.
                    if (otherInvites.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("share_invite.other_invites"),
                        ) {
                            Text(
                                text = stringResource(R.string.share_invite_direct_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.share_invite_direct_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            for (row in otherInvites) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = row.label
                                            ?: stringResource(R.string.share_invite_older_link),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { viewModel.revoke(row, groupId) },
                                        modifier = Modifier
                                            .testTag(
                                                "share_invite.revoke_button." +
                                                    (row.label ?: "superseded"),
                                            ),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.share_invite_revoke),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is ShareInviteViewModel.State.Failed -> {
                    Text(
                        text = stringResource(R.string.share_invite_failed, s.reason),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.mintFor(groupId) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onDone) {
                Text(
                    stringResource(R.string.share_invite_skip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
