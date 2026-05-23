package app.onym.android.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.onym.android.R
import app.onym.android.scan.QrScannerScreen
import app.onym.android.transport.DeeplinkCapture
import kotlinx.coroutines.launch

/**
 * "Scan to join" surface: reuses the generic [QrScannerScreen] camera
 * UI and runs whatever it decodes through the **same**
 * [DeeplinkCapture] allowlist + [IntroCapability] decoder the
 * tapped-link path uses ([app.onym.android.MainActivity] →
 * `DeeplinkCapture.introCapabilityFromIntent`). So a scanned QR and a
 * tapped `https://onym.app/join?c=…` link reach the join flow
 * identically — no second decoder, no second join path.
 *
 * A scanned string that isn't an Onym invite (decoder returns null —
 * wrong host/scheme, or a malformed `c=` payload) shows a snackbar and
 * re-arms the camera instead of starting a join. Re-arming is done by
 * re-[key]ing the scanner: its one-shot latch lives in a `remember`
 * inside [QrScannerScreen], so swapping the key gives a fresh latch and
 * lets the user line up another code.
 *
 * Android twin of the scan-and-route logic in `ChatsView.swift`
 * (onym-ios PR #168), which parses via
 * `DeeplinkCapture.introCapability(fromString:)` and drives the same
 * JoinView the deeplink uses.
 */
@Composable
fun ScanToJoinScreen(
    onCapability: (IntroCapability) -> Unit,
    onCancel: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notOnymMessage = stringResource(R.string.scan_join_not_onym)
    // Bumped on each rejected scan to re-arm the scanner's one-shot
    // latch (see KDoc).
    var attempt by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        key(attempt) {
            QrScannerScreen(
                onScanned = { raw ->
                    val capability = DeeplinkCapture.introCapabilityFromUri(raw)
                    if (capability != null) {
                        onCapability(capability)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(notOnymMessage) }
                        attempt++
                    }
                },
                onCancel = onCancel,
            )
        }
        // Sits above the scanner's own chrome; system-bar padding keeps
        // it clear of the gesture bar.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding(),
        )
    }
}
