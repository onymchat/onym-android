package chat.onym.android.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Subscribe to [IdentityRepository.snapshots] and render whatever the
 * repo publishes. Drives `bootstrap()` from a [LaunchedEffect] so first
 * launch generates a fresh BIP39 identity and persists it; later
 * launches load the same identity from EncryptedSharedPreferences.
 *
 * This screen exists to make the repo wiring exercisable end-to-end.
 * Real onboarding / recovery / settings UI lands in subsequent chunks.
 */
@Composable
fun IdentityBootstrapScreen(
    repository: IdentityRepository,
    modifier: Modifier = Modifier,
) {
    val identity by repository.snapshots.collectAsStateWithLifecycle()
    var bootstrapError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        try {
            repository.bootstrap()
        } catch (t: Throwable) {
            bootstrapError = t.message ?: t::class.qualifiedName
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Identity repository",
            style = MaterialTheme.typography.titleLarge,
        )

        when {
            bootstrapError != null -> {
                Text(
                    text = bootstrapError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            identity == null -> {
                CircularProgressIndicator()
                Text("Loading identity…", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                IdentitySection(identity!!)
            }
        }
    }
}

@Composable
private fun IdentitySection(identity: Identity) {
    Labelled("Nostr public key (hex, 32 bytes)") { hexLine(identity.nostrPublicKey) }
    Labelled("BLS12-381 public key (hex, 48 bytes)") { hexLine(identity.blsPublicKey) }
    Labelled("Stellar account ID (StrKey, 56 chars)") {
        Text(identity.stellarAccountID, style = monospace())
    }
    Labelled("Inbox public key — X25519 (hex, 32 bytes)") { hexLine(identity.inboxPublicKey) }
    Labelled("Inbox tag (Nostr filter)") {
        Text(identity.inboxTag, style = monospace())
    }
    // First-launch reveal of the freshly-generated mnemonic so the user
    // can write it down before doing anything else with the app. Real
    // onboarding / settings UI in a later chunk will gate this behind
    // BiometricPrompt + an explicit "show recovery phrase" action; for
    // now it shows on every app launch because there's nothing else for
    // this scaffold screen to do.
    // onym:allow-secret-read
    identity.recoveryPhrase?.let { phrase ->
        Labelled("Recovery phrase (BIP39, 12 words)") {
            Text(phrase, style = monospace())
        }
    }
}

@Composable
private fun Labelled(caption: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun hexLine(bytes: ByteArray) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            append(bytes.toHex())
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun monospace() = MaterialTheme.typography.bodySmall.copy(
    fontFamily = FontFamily.Monospace,
)

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append("%02x".format(b.toInt() and 0xFF))
    }
    return sb.toString()
}
