package chat.onym.android.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Joiner-side post-deeplink screen. The user lands here after tapping
 * `https://onym.chat/join?c=…` (App Link) or `onym://join?c=…`
 * (custom-scheme fallback). Renders the inviter's intro context,
 * collects a display label, ships the join request via
 * [JoinViewModel.send], and surfaces the wait-for-approval / approved
 * states.
 *
 * Test tags:
 *  - `join.send_button` — primary CTA in [JoinViewModel.State.Ready]
 *    and [JoinViewModel.State.Failed].
 *  - `join.label_field` — display-label TextField.
 *  - `join.sending` — progress spinner in [JoinViewModel.State.Sending].
 *  - `join.awaiting_approval` — copy block in
 *    [JoinViewModel.State.AwaitingApproval].
 *  - `join.approved` — copy block in [JoinViewModel.State.Approved].
 *  - `join.failed_reason` — error copy in [JoinViewModel.State.Failed].
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(
    viewModel: JoinViewModel,
    onBackClick: () -> Unit,
    onOpenChat: (ChatGroup) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var displayLabel by remember { mutableStateOf(viewModel.suggestedDisplayLabel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join chat") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = viewModel.capability.groupName ?: "Invite to join a chat",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "from ${viewModel.capability.introPublicKey.toShortFingerprint()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            when (val s = state) {
                is JoinViewModel.State.Ready,
                is JoinViewModel.State.Failed -> {
                    OutlinedTextField(
                        value = displayLabel,
                        onValueChange = { displayLabel = it.take(64) },
                        label = { Text("How should the inviter see you?") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join.label_field"),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The inviter sees this name in their approval prompt. " +
                            "They can decline if it doesn't match what they expect.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s is JoinViewModel.State.Failed) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = s.reason,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("join.failed_reason"),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { viewModel.send(displayLabel) },
                        enabled = displayLabel.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join.send_button"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(if (s is JoinViewModel.State.Failed) "Try again" else "Send join request")
                    }
                }

                is JoinViewModel.State.Sending -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("join.sending"),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Sending request…", style = MaterialTheme.typography.bodyMedium)
                }

                is JoinViewModel.State.AwaitingApproval -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.testTag("join.awaiting_approval"),
                    ) {
                        Icon(
                            Icons.Filled.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Waiting for approval",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "You'll see this chat appear in your list as soon as " +
                                "the inviter taps Approve. You can close this screen — " +
                                "we'll add it to Chats automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = onBackClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Back to chats")
                        }
                    }
                }

                is JoinViewModel.State.Approved -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.testTag("join.approved"),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Joined!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "You're a member of ${s.group.name}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { onOpenChat(s.group) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open chat")
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

/** First 4 bytes of the intro pubkey, hex-encoded — short enough to
 *  read off the screen, long enough to disambiguate two captures
 *  during testing. Same shape used by the placeholder in PR-6. */
private fun ByteArray.toShortFingerprint(): String =
    take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + "…"
