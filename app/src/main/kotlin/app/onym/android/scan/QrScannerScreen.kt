package app.onym.android.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.onym.android.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen camera surface that scans a QR code and reports the
 * decoded payload via [onScanned], or backs out via [onCancel].
 *
 * Deliberately generic — it doesn't know the payload means an inbox
 * key. The caller (today, the Create Group "Invite by inbox key"
 * screen) runs the decoded string through
 * [app.onym.android.group.canonicalizeInviteKey]. Keeping the parser
 * out of the scanner lets the same surface scan future payload shapes.
 *
 * Android twin of `QRCodeScannerView.swift` from onym-ios: centred
 * viewfinder, top-right cancel, bottom hint, and a one-shot delivery
 * so a held-up code fires [onScanned] exactly once.
 *
 * Theming: while the camera is live, the overlay chrome (hint,
 * viewfinder border, cancel scrim) stays light-on-dark — it sits on
 * the camera feed, so a fixed dark treatment reads regardless of the
 * app theme, matching the iOS twin. The *non-camera* surfaces (the
 * blank/denied background, the permission-denied message, and the
 * cancel button once the feed is gone) follow the app's light/dark
 * scheme via [MaterialTheme.colorScheme], which the enclosing
 * `OnymTheme` provides.
 *
 * Camera permission is requested on first composition. Denial swaps
 * to an inline message — the caller's paste-the-key path is the
 * fallback, so we don't hard-block here.
 */
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionRequested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Black behind the live feed (camera letterboxing reads as
            // black); the themed surface only shows in the no-camera
            // states (permission flow / denied / bind failure).
            .background(
                if (hasPermission) Color.Black
                else MaterialTheme.colorScheme.background,
            ),
    ) {
        if (hasPermission) {
            CameraPreview(onScanned = onScanned)
            Viewfinder()
        } else if (permissionRequested) {
            PermissionDenied()
        }

        // Bottom hint — only meaningful while the camera is live.
        if (hasPermission) {
            Text(
                text = stringResource(R.string.qr_scanner_hint),
                color = Color.White,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 40.dp),
            )
        }

        // Top-right cancel — shown in every state. Over the live feed
        // it keeps the dark scrim + white glyph; on the themed
        // no-camera surface it switches to the scheme's surface chip.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(8.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (hasPermission) Color.Black.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = if (hasPermission) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** CameraX preview + QR analysis bound to the composition lifecycle. */
@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // Single-thread analysis executor; shut down on dispose.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // One-shot latch — AVFoundation's iOS twin has the same guard;
    // STRATEGY_KEEP_ONLY_LATEST still hands us several frames of the
    // same held-up code, and we must fire onScanned only once.
    val consumed = remember { AtomicBoolean(false) }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        analysisExecutor,
                        QrCodeAnalyzer { text ->
                            if (consumed.compareAndSet(false, true)) {
                                // Hop to the main thread; onScanned
                                // tears down this composable.
                                previewView.post { onScanned(text) }
                            }
                        },
                    )
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (_: Exception) {
                // No back camera / camera in use — leave the surface
                // black; the cancel button and paste fallback remain.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                providerFuture.get().unbindAll()
            } catch (_: Exception) {
                // Provider never resolved — nothing bound to release.
            }
            analysisExecutor.shutdown()
        }
    }
}

/** Centred square viewfinder, non-interactive — pure chrome. */
@Composable
private fun BoxScope.Viewfinder() {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.65f)
            .aspectRatio(1f)
            .border(
                width = 2.dp,
                color = Color.White.copy(alpha = 0.85f),
                shape = RoundedCornerShape(18.dp),
            ),
    )
}

@Composable
private fun BoxScope.PermissionDenied() {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.NoPhotography,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.qr_scanner_permission_denied),
            color = MaterialTheme.colorScheme.onBackground,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
