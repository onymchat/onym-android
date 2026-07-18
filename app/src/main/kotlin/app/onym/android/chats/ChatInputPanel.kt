package app.onym.android.chats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
    onAttach: (() -> Unit)? = null,
    onAttachVideo: (() -> Unit)? = null,
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
        if (onAttach != null) {
            IconButton(
                onClick = onAttach,
                enabled = enabled,
                modifier = Modifier.testTag("chat_thread.attach_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = "Attach photo",
                )
            }
        }
        if (onAttachVideo != null) {
            IconButton(
                onClick = onAttachVideo,
                enabled = enabled,
                modifier = Modifier.testTag("chat_thread.attach_video_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = "Attach video",
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
                // Disabled state must visually contrast with the
                // adjacent text field (also `surfaceVariant`) so the
                // button reads as "greyed out", not as an extension of
                // the field. Match M3's standard low-alpha disabled
                // palette — a tinted overlay against the screen surface
                // rather than a solid fill that blends with the field.
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
