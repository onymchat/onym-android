package chat.onym.android.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Placeholder destination for inbound `https://onym.chat/join?c=…`
 * deeplinks. **PR-7 will replace this** with `JoinScreen` +
 * `JoinViewModel` that ships the actual join request via
 * [JoinRequestSender] and watches for the sealed invitation to
 * arrive. PR-6's job is just to prove the intent-filter wiring
 * works end-to-end (tap a link → land on a screen that knows the
 * capability).
 *
 * Renders the inviter's intro pubkey hex prefix + group_id hex
 * prefix so a manual tester can confirm the right capability was
 * decoded from the URL. No interaction wired beyond Back.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JoinInviteCapturedPlaceholder(
    capability: IntroCapability,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join invite") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = capability.groupName ?: "Invite to join a chat",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Captured from deeplink. PR-7 wires the actual " +
                    "send-request UI; this screen is a stand-in so the " +
                    "intent-filter wiring can be exercised manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            // Hex prefixes — short enough to read off the screen but
            // long enough to disambiguate two captures during testing.
            Text(
                text = "intro_pub: ${capability.introPublicKey.toHexPrefix()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "group_id: ${capability.groupId.toHexPrefix()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back to chats")
            }
        }
    }
}

private fun ByteArray.toHexPrefix(): String =
    take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + "…"
