package app.onym.android.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.strings.R

/**
 * The thread behind a chat you are not in yet.
 *
 * It is deliberately thread-shaped rather than a form: the row sits in
 * the chats list next to real conversations, so tapping it has to land
 * somewhere that reads like a conversation which hasn't started. The
 * founder's invitation message is the one thing already said, and the
 * rest of the screen is the wait — the same states the Invitations
 * screen used to show, in the place people actually look.
 *
 * When the founder approves, the group materializes, the pending row is
 * consumed and this screen is replaced by the real thread — whose first
 * row is the "You joined X" notice the dispatcher mints on
 * materialization. Nothing here writes that notice; this screen just
 * stops existing.
 *
 * Mirrors `PendingChatThreadView` in onym-ios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingChatThreadScreen(
    viewModel: PendingChatsViewModel,
    rowId: String,
    onBack: () -> Unit,
    /** Accept opens the confirmation screen rather than sending: the
     *  same review, and the same chance to choose a name, whichever
     *  door the invitation came through. */
    onAccept: (String) -> Unit,
    /** Called with the hex group id once this wait is over. The screen
     *  doesn't navigate itself: the back stack belongs to the host, and
     *  a screen that pops the stack it is standing on is a rule this
     *  codebase already avoids. */
    onMaterialized: (String) -> Unit,
) {
    LaunchedEffect(viewModel) { viewModel.start() }

    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val materialized by viewModel.materialized.collectAsStateWithLifecycle()
    val rowsReady by viewModel.rowsReady.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.id == rowId }
    val landedGroupId = materialized[rowId]

    // The whole point of the flow: the founder approves, the group
    // materializes, and the person watching the wait is put straight
    // into the chat — landing on the "You joined X" notice rather than
    // on a spinner they have to back out of.
    LaunchedEffect(landedGroupId) {
        landedGroupId?.let(onMaterialized)
    }
    // The other way a row ends: dismissed from the list. Nothing to swap
    // to, so leave rather than stand on a screen whose subject is gone.
    //
    // Unless the group is already here. A verification is cleared by a
    // collector this screen doesn't own, so the row can vanish a frame
    // before the handoff is recorded — and popping in that frame would
    // drop the person one screen back at the moment their chat arrived.
    LaunchedEffect(row, landedGroupId, rowsReady) {
        if (rowsReady && row == null && landedGroupId == null &&
            !viewModel.groupHasLanded(rowId.substringBefore(':'))
        ) {
            onBack()
        }
    }

    val title = row?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.pending_chat_unnamed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        modifier = Modifier.testTag("pending_chat.thread"),
    ) { padding ->
        if (row == null) {
            // One frame at most: the effects above pop or swap us the
            // moment the row goes.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            lastError?.let { error ->
                ErrorBanner(error) { viewModel.dismissError() }
            }
            Spacer(Modifier.height(16.dp))
            Header(row = row, title = title)
            row.invitationMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(16.dp))
                InvitationCard(message)
            }
            Spacer(Modifier.height(20.dp))
            StateBlock(row = row, viewModel = viewModel, onAccept = onAccept)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(row: PendingChatsViewModel.Row, title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        val alias = row.inviterAlias.trim()
        if (alias.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pending_chat_invited_you, alias),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The founder's own words, rendered as the one message this thread
 *  already has. */
@Composable
private fun InvitationCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("pending_chat.invitation_message"),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun StateBlock(
    row: PendingChatsViewModel.Row,
    viewModel: PendingChatsViewModel,
    onAccept: (String) -> Unit,
) {
    when (row.state) {
        PendingChatsViewModel.State.Offered -> {
            Text(
                text = stringResource(R.string.pending_chat_offer_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAccept(row.id) },
                enabled = !row.isSending,
                modifier = Modifier.fillMaxWidth().testTag("pending_chat.accept"),
            ) {
                Text(
                    if (row.isSending) {
                        stringResource(R.string.pending_chat_sending)
                    } else {
                        stringResource(R.string.pending_chat_accept)
                    },
                )
            }
        }

        PendingChatsViewModel.State.Waiting -> {
            Waiting(
                title = stringResource(R.string.pending_chat_waiting_title),
                body = stringResource(R.string.pending_chat_waiting_body),
            )
            Spacer(Modifier.height(16.dp))
            // A request can be sent and never answered: a revoked link,
            // or one that died in a relay. Without this the only way out
            // was to swipe the row away and find the invite again.
            // Asking twice is safe — the founder's side collapses
            // repeats from the same joiner, and a decline stays
            // declined.
            OutlinedButton(
                onClick = { viewModel.retry(row.id) },
                enabled = !row.isSending,
                modifier = Modifier.fillMaxWidth().testTag("pending_chat.ask_again"),
            ) {
                Text(
                    if (row.isSending) {
                        stringResource(R.string.pending_chat_sending)
                    } else {
                        stringResource(R.string.pending_chat_ask_again)
                    },
                )
            }
        }

        PendingChatsViewModel.State.ChainSettling -> Waiting(
            title = stringResource(R.string.pending_chat_almost_in),
            body = stringResource(R.string.invite_chain_settling),
        )

        else -> Stuck(row = row, viewModel = viewModel)
    }
}

@Composable
private fun Waiting(title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("pending_chat.waiting"),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One sentence per reason, because the reasons need different things
 * from the reader — naming the founder for a failure that is entirely
 * local sends people to wait on somebody who cannot help. Same wording
 * the Invitations screen carried, one screen over.
 */
@Composable
private fun Stuck(
    row: PendingChatsViewModel.Row,
    viewModel: PendingChatsViewModel,
) {
    val message = when (val state = row.state) {
        PendingChatsViewModel.State.ChainNotConfigured ->
            stringResource(R.string.invite_chain_not_configured)
        PendingChatsViewModel.State.ChainUnreachable ->
            stringResource(R.string.invite_chain_unreachable)
        is PendingChatsViewModel.State.SendFailed -> when (state.failure) {
            PendingChat.SendFailure.NO_IDENTITY -> stringResource(R.string.pending_chat_no_identity)
            PendingChat.SendFailure.TRANSPORT -> stringResource(R.string.pending_chat_send_failed)
        }
        else -> stringResource(R.string.invite_verify_failed)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("pending_chat.stuck"),
        )
        if (row.state.isRetryable) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.retry(row.id) },
                enabled = !row.isSending,
                modifier = Modifier.fillMaxWidth().testTag("pending_chat.retry"),
            ) {
                Text(
                    if (row.isSending) {
                        stringResource(R.string.pending_chat_sending)
                    } else {
                        stringResource(R.string.pending_chat_retry)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: PendingChatError, onDismiss: () -> Unit) {
    val text = when (error) {
        PendingChatError.MalformedInvite -> stringResource(R.string.pending_chat_malformed)
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(0.85f),
            )
            // Close, not the banner's own warning glyph: the button
            // dismisses the message, and repeating the icon beside it
            // read as a second warning rather than a way out of the
            // first — with nothing for a screen reader to announce.
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dismiss),
                )
            }
        }
    }
}
