package app.onym.android.chats

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Chat-thread composer. A `TextField` that grows to a 3-line cap
 * (then scrolls internally) with a trailing Send button. Tapping
 * Send fires [onSend] with the body trimmed of leading and trailing
 * whitespace, then clears the field. Whitespace-only input never
 * fires the callback — the button stays disabled and the trim guard
 * in [trimmedSendBody] is the belt-plus-braces check.
 *
 * Keyboard avoidance is the parent's responsibility: place this
 * inside a `Modifier.imePadding()`-bounded layout. With no keyboard
 * up the padding is zero; when the keyboard rises the parent
 * automatically slides up — same affordance as iOS's
 * `view.keyboardLayoutGuide.topAnchor`.
 *
 * Text state is held internally via `remember { mutableStateOf("") }`.
 * The caller has no view on the in-flight body; it only sees the
 * post-send callback. A future "draft persistence" surface (next PR
 * or beyond) could hoist this state into the ViewModel.
 *
 * Mirrors `ChatInputPanelView.swift` from onym-ios PR #153. Diverges
 * in two ways the platform invites:
 *  - No manual `intrinsicContentSize` measurement — `OutlinedTextField`
 *    + `maxLines = 3` natively grows-then-scrolls.
 *  - No `keyboardLayoutGuide` plumbing — `Modifier.imePadding()` on
 *    the parent does the same job declaratively.
 */
@Composable
fun ChatInputPanel(
    onSend: (body: String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = MAX_LINES,
    focusRequester: FocusRequester? = null,
    /** Tapped the single attach button → combined photo/video picker. */
    onAttach: (() -> Unit)? = null,
    /** A voice message finished recording (mic released past the minimum
     *  hold, not cancelled). The host encrypts + uploads it. */
    onSendVoice: ((ChatVoiceRecorder.Recording) -> Unit)? = null,
    /** Loopback-harness only: send a canned voice clip on a plain mic tap
     *  (a real press-and-hold recording can't be driven from a UI test). */
    onSendVoiceCanned: (() -> Unit)? = null,
    /** Media staged in the two-step send flow, shown as a removable
     *  thumbnail strip above the composer. */
    pendingMedia: List<PendingMediaItem> = emptyList(),
    /** Drop a staged item (its ✕ was tapped). */
    onRemovePending: ((java.util.UUID) -> Unit)? = null,
    /** Confirm-send the staged media as one message/album. */
    onSendMedia: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    val sendBody = trimmedSendBody(text)
    val hasPendingMedia = pendingMedia.isNotEmpty()
    // With media staged, Send ships the album (no caption this
    // iteration); otherwise it ships the trimmed text body.
    val canSend = enabled && (hasPendingMedia || sendBody != null)

    // Voice-recording UI state, driven by the mic button's press-and-hold.
    // While recording, the attach + text field are replaced by a record
    // indicator (red dot + timer + "slide to cancel"); the mic stays put on
    // the trailing edge so the finger keeps its pointer stream.
    var isRecording by remember { mutableStateOf(false) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var recordingWillCancel by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (hasPendingMedia) {
            MediaPreviewStrip(
                items = pendingMedia,
                onRemove = onRemovePending,
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("chat_thread.input_panel"),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isRecording) {
            RecordingIndicator(
                elapsedMs = recordingElapsedMs,
                willCancel = recordingWillCancel,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (onAttach != null) {
                // Single circular paperclip attach button → combined picker.
                IconButton(
                    onClick = onAttach,
                    enabled = enabled,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("chat_thread.attach_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach photo or video",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(BUBBLE_CORNER))
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .testTag("chat_thread.input_field"),
                enabled = enabled,
                placeholder = { Text("Message") },
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(BUBBLE_CORNER),
            )
        }
        // Right side toggles: mic when the composer is empty (or actively
        // recording), Send once there's text or staged media.
        val showMic = enabled && onSendVoice != null &&
            (isRecording || (sendBody == null && !hasPendingMedia))
        if (showMic) {
            VoiceMicButton(
                onSendVoice = onSendVoice,
                onSendVoiceCanned = onSendVoiceCanned,
                onRecordingChange = { recording, elapsedMs, willCancel ->
                    isRecording = recording
                    recordingElapsedMs = elapsedMs
                    recordingWillCancel = willCancel
                },
            )
        } else {
            FilledIconButton(
                onClick = {
                    // Staged media takes precedence — confirm-send the album.
                    if (hasPendingMedia) {
                        onSendMedia?.invoke()
                        return@FilledIconButton
                    }
                    val body = trimmedSendBody(text) ?: return@FilledIconButton
                    onSend(body)
                    text = ""
                },
                enabled = canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = 0.38f),
                ),
                modifier = Modifier.testTag("chat_thread.send_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                )
            }
        }
    }
    }
}

/**
 * Trailing mic button. Held to record a voice message (via
 * [ChatVoiceRecorder]); released past the minimum hold to send; slid left
 * past a threshold to cancel. Reports recording state through
 * [onRecordingChange] so the composer can swap the text field for a record
 * indicator. Under the loopback harness ([onSendVoiceCanned] non-null) a
 * plain tap sends a canned clip instead (a press-and-hold can't be driven
 * from a UI test).
 */
@Composable
private fun VoiceMicButton(
    onSendVoice: ((ChatVoiceRecorder.Recording) -> Unit)?,
    onSendVoiceCanned: (() -> Unit)?,
    onRecordingChange: (recording: Boolean, elapsedMs: Long, willCancel: Boolean) -> Unit,
) {
    val context = LocalContext.current

    if (onSendVoiceCanned != null) {
        FilledIconButton(
            onClick = onSendVoiceCanned,
            modifier = Modifier.testTag("chat_thread.mic_button"),
        ) {
            Icon(imageVector = Icons.Filled.Mic, contentDescription = "Record voice message")
        }
        return
    }

    val recorder = remember { ChatVoiceRecorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    var recording by remember { mutableStateOf(false) }
    var willCancel by remember { mutableStateOf(false) }
    val latestOnSend by rememberUpdatedState(onSendVoice)
    val cancelThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // Report elapsed time (and live willCancel flips) while recording.
    LaunchedEffect(recording) {
        while (recording) {
            onRecordingChange(true, (recorder.elapsedSeconds * 1000).toLong(), willCancel)
            kotlinx.coroutines.delay(100)
        }
    }

    FilledIconButton(
        onClick = {},
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (recording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .testTag("chat_thread.mic_button")
            .pointerInput(hasPermission) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        var e = awaitPointerEvent()
                        while (e.changes.any { it.pressed }) e = awaitPointerEvent()
                        return@awaitEachGesture
                    }
                    if (!recorder.start()) return@awaitEachGesture
                    recording = true
                    willCancel = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        willCancel = (change.position.x - down.position.x) < -cancelThresholdPx
                        if (!change.pressed) break
                    }
                    recording = false
                    onRecordingChange(false, 0, false)
                    if (willCancel) {
                        recorder.cancel()
                    } else {
                        val result = recorder.stop()
                        if (result == null) {
                            recorder.cancel()
                        } else if (result.durationSeconds < ChatVoiceRecorder.MINIMUM_DURATION_SECONDS) {
                            runCatching { result.file.delete() }
                        } else {
                            latestOnSend?.invoke(result)
                        }
                    }
                }
            },
    ) {
        Icon(imageVector = Icons.Filled.Mic, contentDescription = "Record voice message")
    }
}

/**
 * Record-time indicator shown in place of the attach button + text field
 * while a voice message is recording: a red dot, an `m:ss` timer, and a
 * "slide to cancel" hint (turning red once the drag crosses the cancel
 * threshold). Mirrors the iOS composer's recording overlay.
 */
@Composable
private fun RecordingIndicator(
    elapsedMs: Long,
    willCancel: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .testTag("chat_thread.recording"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Spacer(Modifier.width(8.dp))
        val seconds = (elapsedMs / 1000).toInt()
        Text(
            text = String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (willCancel) "release to cancel" else "‹ slide to cancel",
            color = if (willCancel) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Horizontal strip of staged-media thumbnails shown above the composer
 * in the two-step send flow. Each tile carries a ✕ to drop it before
 * sending; videos get a play glyph. Mirrors the iOS
 * `ChatInputPanelView` media preview strip.
 */
@Composable
private fun MediaPreviewStrip(
    items: List<PendingMediaItem>,
    onRemove: ((java.util.UUID) -> Unit)?,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("chat_thread.input.media_strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = items, key = { it.id }) { item ->
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .testTag("chat_thread.input.media_strip.tile"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb = item.thumbnail
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (item.source is ChatMediaSource.Video) {
                        Icon(
                            imageVector = Icons.Filled.PlayCircle,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (onRemove != null) {
                    IconButton(
                        onClick = { onRemove(item.id) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .testTag("chat_thread.input.media_strip.remove"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Trims [text] of leading and trailing whitespace and returns the
 * result if non-empty, or `null` otherwise. The send button's
 * enabled state and the actual send invocation both consult this
 * — UI never ships a whitespace-only body. Extracted as a pure
 * function so the policy is unit-testable without standing up
 * Compose.
 */
internal fun trimmedSendBody(text: String): String? =
    text.trim().takeIf { it.isNotEmpty() }

/** Max line count the composer grows to before scrolling internally.
 *  3 matches the iOS twin. Exposed via the [ChatInputPanel.maxLines]
 *  parameter so a future "fullscreen composer" mode can override. */
internal const val MAX_LINES = 3

private val BUBBLE_CORNER = 20.dp
