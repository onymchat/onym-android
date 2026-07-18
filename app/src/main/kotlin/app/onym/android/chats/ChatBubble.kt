package app.onym.android.chats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Chat-thread bubble. Two visual modes driven by [ChatMessage.direction]:
 *
 *  - [MessageDirection.OUTGOING] — filled with the sender's accent
 *    ([ChatSenderDisplay.accent], which for own messages hashes to the
 *    user's own color), `onAccent` text, trailing-aligned. Status glyph
 *    below the trailing edge (clock / check / red bang).
 *  - [MessageDirection.INCOMING] — tinted with the sender's accent at
 *    [INCOMING_TINT_ALPHA], `onSurface` text for readability,
 *    leading-aligned, optionally topped by an accent-colored name
 *    header. No status indicator (the message arriving is proof of
 *    delivery).
 *
 * Sender differentiation (onym-ios PR #162): there are no avatars, so
 * consecutive incoming messages from one person are grouped under a
 * single accent-colored name header ([ChatSenderDisplay.showNameHeader],
 * decided by the screen's run-grouping pass) and the bubble carries
 * that sender's accent. The accent is a hash of the BLS pubkey, not the
 * alias, so it doubles as a cheap visual fingerprint an alias-spoofer
 * can't forge. The bubble stays a dumb renderer — it receives a
 * resolved [ChatSenderDisplay] and never looks up aliases or hashes
 * pubkeys itself.
 *
 * Bubble max width is capped at [maxWidthFraction] of the parent
 * row (default 75%) so a long monologue doesn't reach the opposite
 * edge.
 *
 * Failed outgoing bubbles register a tap target that fires [onRetry]
 * (when provided). Non-failed bubbles are unclickable — the gate
 * lives inside [canRetry] so a stale tap that arrives after the
 * status flipped to SENT is a silent no-op, not a double-delivery.
 *
 * Compose's natural recomposition over the [ChatMessage] data class
 * means a status flip (PENDING → SENT / FAILED) re-renders the
 * bubble (and swaps the glyph) without any explicit
 * diffable-identity widening — the outer `LazyColumn(items, key =
 * { it.id })` keeps the slot stable; this body reads the latest
 * fields. iOS PR #155 had to call `snapshot.reconfigureItems(...)`
 * to close the equivalent gap; Compose covers it for free.
 *
 * Mirrors `ChatBubbleCell.swift` from onym-ios PR #155.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    sender: ChatSenderDisplay = ChatSenderDisplay.Unknown,
    modifier: Modifier = Modifier,
    maxWidthFraction: Float = 0.75f,
    onRetry: (() -> Unit)? = null,
    imageLoader: ChatImageLoader? = null,
    onImageTapped: ((ChatImageAttachment) -> Unit)? = null,
    onVideoTapped: ((ChatVideoAttachment) -> Unit)? = null,
    /** Tap on an album item at the given index — opens the full-screen
     *  gallery there. Only wired for sent (non-pending, non-failed) media. */
    onAlbumItemTapped: ((Int) -> Unit)? = null,
    /** Tap on a failed outgoing media message — surfaces the Resend /
     *  Delete menu. Distinct from [onRetry] (text messages retry on a
     *  plain tap). */
    onFailedMediaTap: (() -> Unit)? = null,
    reply: ChatReplyQuote? = null,
    onQuoteTap: (() -> Unit)? = null,
    isHighlighted: Boolean = false,
    onSwipeReply: (() -> Unit)? = null,
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    // The Chats tab is Material-themed (no OnymTheme provider), so we
    // resolve the accent against the system theme directly — the no-arg
    // OnymAccent.color() would always return the dark variant here.
    val darkTheme = isSystemInDarkTheme()
    val accentColor: Color = sender.accent.color(darkTheme)
    val fill: Color = if (isOutgoing) {
        // Own messages: solid fill in the user's own accent (so "your
        // color" is consistent with the members list and every group).
        accentColor
    } else {
        // Others' messages: low-opacity tint in the sender's accent —
        // distinguishable per person while keeping body text readable.
        accentColor.copy(alpha = INCOMING_TINT_ALPHA)
    }
    val textColor: Color = if (isOutgoing) {
        // White on the darker light-variant fills, black on the bright
        // dark-variant fills — mirrors OnymTokens.onAccent.
        if (darkTheme) Color.Black else Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val retryEnabled = canRetry(message) && onRetry != null

    // Drag-to-reply: the row follows the finger leftward (clamped),
    // a reply glyph grows in at the trailing edge, a one-shot haptic
    // fires as the drag crosses the threshold, and releasing past the
    // threshold arms the reply; otherwise everything springs back.
    // Available on every message regardless of direction or status.
    // Mirrors `ChatBubbleCell` swipe-to-reply from onym-ios PR #175.
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val maxTravelPx = with(density) { SWIPE_MAX_TRAVEL.toPx() }
    val thresholdPx = with(density) { SWIPE_REPLY_THRESHOLD.toPx() }
    val dragOffset = remember { Animatable(0f) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var swipeArmed by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        val bubbleMaxWidth = maxWidth * maxWidthFraction
        if (onSwipeReply != null) {
            val progress = (-dragOffset.value / thresholdPx).coerceIn(0f, 1f)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(22.dp)
                    .graphicsLayer {
                        alpha = progress
                        val s = 0.6f + 0.4f * progress
                        scaleX = s
                        scaleY = s
                    },
            )
        }
        val swipeModifier = if (onSwipeReply != null) {
            Modifier.pointerInput(onSwipeReply) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragAccumulated = 0f
                        swipeArmed = false
                    },
                    onDragEnd = {
                        val fire = swipeArmed
                        swipeArmed = false
                        dragAccumulated = 0f
                        scope.launch {
                            dragOffset.animateTo(0f, spring(dampingRatio = 0.7f))
                        }
                        if (fire) onSwipeReply()
                    },
                    onDragCancel = {
                        swipeArmed = false
                        dragAccumulated = 0f
                        scope.launch { dragOffset.animateTo(0f, spring(dampingRatio = 0.7f)) }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    // Leftward only; clamp travel so the row can't be
                    // dragged off-screen and the armed point reads as a wall.
                    dragAccumulated = (dragAccumulated + dragAmount)
                        .coerceIn(-maxTravelPx, 0f)
                    scope.launch { dragOffset.snapTo(dragAccumulated) }
                    val pastThreshold = -dragAccumulated >= thresholdPx
                    if (pastThreshold && !swipeArmed) {
                        swipeArmed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (!pastThreshold) {
                        swipeArmed = false
                    }
                }
            }
        } else {
            Modifier
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                .then(swipeModifier),
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
            ) {
                // Accent-colored sender name at the start of an incoming
                // run. Suppressed mid-run, on outgoing rows, and in
                // 1-on-1 groups — the screen's run-grouping pass decides
                // via ChatSenderDisplay.showNameHeader.
                if (sender.showNameHeader) {
                    Text(
                        text = sender.name,
                        color = accentColor,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .testTag("chat_thread.sender.${message.id}"),
                    )
                }
                val hasMedia = message.media.isNotEmpty()
                BubbleBody(
                    body = message.body,
                    fill = fill,
                    textColor = textColor,
                    messageId = message.id,
                    // Text messages retry on a plain bubble tap; media
                    // messages route the failed tap to the Resend/Delete
                    // menu inside the media overlay instead.
                    onClick = if (retryEnabled && !hasMedia) onRetry else null,
                    media = message.media,
                    status = message.status,
                    imageLoader = imageLoader,
                    onImageTapped = onImageTapped,
                    onVideoTapped = onVideoTapped,
                    onAlbumItemTapped = onAlbumItemTapped,
                    onFailedMediaTap = onFailedMediaTap,
                    reply = reply,
                    replyAccentColor = reply?.accent?.color(darkTheme),
                    isOutgoing = isOutgoing,
                    isHighlighted = isHighlighted,
                    onQuoteTap = onQuoteTap,
                )
                if (isOutgoing) {
                    val visual = statusVisualFor(message.status)
                    if (visual != null) {
                        StatusIndicator(
                            visual = visual,
                            messageId = message.id,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleBody(
    body: String,
    fill: Color,
    textColor: Color,
    messageId: java.util.UUID,
    onClick: (() -> Unit)?,
    media: List<ChatMediaAttachment>,
    status: MessageStatus,
    imageLoader: ChatImageLoader?,
    onImageTapped: ((ChatImageAttachment) -> Unit)?,
    onVideoTapped: ((ChatVideoAttachment) -> Unit)?,
    onAlbumItemTapped: ((Int) -> Unit)?,
    onFailedMediaTap: (() -> Unit)?,
    reply: ChatReplyQuote?,
    replyAccentColor: Color?,
    isOutgoing: Boolean,
    isHighlighted: Boolean,
    onQuoteTap: (() -> Unit)?,
) {
    // Brief background pulse when the host scrolls here from a tapped
    // quote — blends the fill toward the surface tint and animates
    // back once the host clears the highlight. Mirrors iOS PR #174's
    // `flashHighlight()`.
    val highlightTarget = if (isHighlighted) {
        lerp(fill, MaterialTheme.colorScheme.onSurface, HIGHLIGHT_BLEND)
    } else {
        fill
    }
    val animatedFill by animateColorAsState(
        targetValue = highlightTarget,
        animationSpec = tween(durationMillis = 220),
        label = "bubbleHighlight",
    )
    val baseModifier = Modifier
        .clip(RoundedCornerShape(BUBBLE_RADIUS))
        .background(animatedFill)
    val clickableModifier = if (onClick != null) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }
    Column(
        modifier = clickableModifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("chat_thread.bubble.$messageId"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (reply != null) {
            ReplyQuoteInset(
                reply = reply,
                replyAccentColor = replyAccentColor,
                isOutgoing = isOutgoing,
                onAccentColor = textColor,
                messageId = messageId,
                // Only an available target is worth jumping to.
                onTap = if (!reply.isUnavailable) onQuoteTap else null,
            )
        }
        if (media.isNotEmpty()) {
            // Pending → dimmed with a spinner; failed → dimmed with an
            // error glyph, tap opens the Resend/Delete menu. Only sent
            // media is tappable-to-view. Mirrors iOS
            // `ChatBubbleCell.applyAttachmentSendState`.
            val sending = isOutgoing && status == MessageStatus.PENDING
            val failed = isOutgoing && status == MessageStatus.FAILED
            Box(
                modifier = Modifier.testTag("chat_thread.media.$messageId"),
            ) {
                if (media.size == 1) {
                    val item = media[0]
                    val videoItem = item.video
                    val imageItem = item.image
                    if (item.isVideo && videoItem != null) {
                        AttachmentVideo(
                            video = videoItem,
                            imageLoader = imageLoader,
                            messageId = messageId,
                            onTap = mediaViewTap(sending, failed, onFailedMediaTap) {
                                onVideoTapped?.invoke(videoItem)
                            },
                        )
                    } else if (imageItem != null) {
                        AttachmentImage(
                            attachment = imageItem,
                            imageLoader = imageLoader,
                            messageId = messageId,
                            onTap = mediaViewTap(sending, failed, onFailedMediaTap) {
                                onImageTapped?.invoke(imageItem)
                            },
                        )
                    }
                } else {
                    AlbumGrid(
                        media = media,
                        imageLoader = imageLoader,
                        messageId = messageId,
                        onTileTap = if (sending) null else { index ->
                            if (failed) onFailedMediaTap?.invoke()
                            else onAlbumItemTapped?.invoke(index)
                        },
                    )
                }
                if (sending || failed) {
                    MediaSendOverlay(
                        sending = sending,
                        messageId = messageId,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        // Image messages may carry an empty caption — skip the text row
        // entirely so the bubble hugs the image with no blank line.
        if (body.isNotEmpty()) {
            Text(
                text = body,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Renders a chat image attachment: a blurhash placeholder that shows
 * immediately, replaced by the decrypted bitmap once [imageLoader]
 * fetches + decrypts the blob from Blossom. The frame keeps the
 * attachment's intrinsic aspect ratio so there's no reflow when the
 * full image lands. Mirrors `ChatBubbleCell.applyAttachment` from
 * onym-ios.
 */
@Composable
private fun AttachmentImage(
    attachment: ChatImageAttachment,
    imageLoader: ChatImageLoader?,
    messageId: java.util.UUID,
    onTap: (() -> Unit)? = null,
) {
    val aspect = if (attachment.width > 0 && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }
    val placeholder = remember(attachment.blurhash) {
        runCatching { Blurhash.decode(attachment.blurhash, 32, 32) }.getOrNull()
    }
    var bitmap by remember(attachment.sha256) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(attachment.sha256, imageLoader) {
        if (imageLoader != null) {
            bitmap = imageLoader.load(attachment)
        }
    }
    val shown = bitmap ?: placeholder
    Box(
        modifier = Modifier
            .widthIn(max = IMAGE_MAX_WIDTH)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(IMAGE_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .testTag("chat_thread.image.$messageId"),
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Renders a chat video attachment: the poster (its own encrypted image
 * blob) loads exactly like a photo — blurhash placeholder → decrypted
 * poster — with a play glyph overlaid and a duration pill in the corner.
 * Tapping opens the full-screen player; the (large) video blob only
 * downloads then. Mirrors `ChatBubbleCell` video rendering from onym-ios.
 */
@Composable
private fun AttachmentVideo(
    video: ChatVideoAttachment,
    imageLoader: ChatImageLoader?,
    messageId: java.util.UUID,
    onTap: (() -> Unit)?,
) {
    val poster = video.poster
    val aspect = if (poster.width > 0 && poster.height > 0) {
        poster.width.toFloat() / poster.height.toFloat()
    } else {
        1f
    }
    val placeholder = remember(poster.blurhash) {
        runCatching { Blurhash.decode(poster.blurhash, 32, 32) }.getOrNull()
    }
    var bitmap by remember(poster.sha256) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(poster.sha256, imageLoader) {
        if (imageLoader != null) {
            bitmap = imageLoader.load(poster)
        }
    }
    val shown = bitmap ?: placeholder
    Box(
        modifier = Modifier
            .widthIn(max = IMAGE_MAX_WIDTH)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(IMAGE_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .testTag("chat_thread.video.$messageId"),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = formatVideoDuration(video.durationSeconds),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * Resolves the tap handler for a single media item given its send
 * state: pending media is untappable, failed media routes to the
 * Resend/Delete menu, sent media opens the viewer. Pure so the state
 * machine is obvious at the call site.
 */
private fun mediaViewTap(
    sending: Boolean,
    failed: Boolean,
    onFailedMediaTap: (() -> Unit)?,
    viewAction: () -> Unit,
): (() -> Unit)? = when {
    sending -> null
    failed -> onFailedMediaTap
    else -> viewAction
}

/**
 * Dimming scrim over outgoing media that's still uploading (spinner)
 * or has failed (error glyph, tap opens the Resend/Delete menu via the
 * media's own click target). Mirrors iOS
 * `ChatBubbleCell.applyAttachmentSendState`.
 */
@Composable
private fun MediaSendOverlay(
    sending: Boolean,
    messageId: java.util.UUID,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(IMAGE_RADIUS))
            .background(Color.Black.copy(alpha = 0.35f))
            .testTag(
                if (sending) "chat_thread.media.sending.$messageId"
                else "chat_thread.media.failed.$messageId",
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Failed — tap to resend or delete",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * A 2-column grid of album thumbnails: 2 items → one row, 3 → 2+1,
 * 4 → 2×2, 5+ → 2×2 with a "+N" scrim on the 4th tile. Tapping a tile
 * opens the full-screen gallery at that index. Mirrors iOS
 * `AlbumGridView`.
 */
@Composable
private fun AlbumGrid(
    media: List<ChatMediaAttachment>,
    imageLoader: ChatImageLoader?,
    messageId: java.util.UUID,
    onTileTap: ((Int) -> Unit)?,
) {
    val shown = media.take(4)
    val extra = media.size - shown.size
    val spacing = 2.dp
    Column(
        modifier = Modifier
            .width(IMAGE_MAX_WIDTH)
            .clip(RoundedCornerShape(IMAGE_RADIUS)),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        shown.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { colIndex, item ->
                    val index = rowIndex * 2 + colIndex
                    AlbumTile(
                        item = item,
                        imageLoader = imageLoader,
                        messageId = messageId,
                        index = index,
                        plusCount = if (index == shown.lastIndex && extra > 0) extra else 0,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onTap = onTileTap?.let { cb -> { cb(index) } },
                    )
                }
                // Odd trailing item keeps its half-width slot rather than
                // stretching across the row.
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(
    item: ChatMediaAttachment,
    imageLoader: ChatImageLoader?,
    messageId: java.util.UUID,
    index: Int,
    plusCount: Int,
    modifier: Modifier,
    onTap: (() -> Unit)?,
) {
    val thumb = item.thumbnail
    val placeholder = remember(thumb.blurhash) {
        runCatching { Blurhash.decode(thumb.blurhash, 32, 32) }.getOrNull()
    }
    var bitmap by remember(thumb.sha256) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(thumb.sha256, imageLoader) {
        if (imageLoader != null) bitmap = imageLoader.load(thumb)
    }
    val shown = bitmap ?: placeholder
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .testTag("chat_thread.album.tile.$index.$messageId"),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(32.dp),
            )
        }
        if (plusCount > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$plusCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

/** Formats a duration in seconds as `m:ss` (e.g. `1:07`). Pure so a
 *  unit test can pin it without Compose. Mirrors iOS
 *  `ChatBubbleCell.formatDuration`. */
internal fun formatVideoDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Inset quote pinned across the top of a bubble that replies to
 * another message: a thin accent bar, the quoted sender's
 * name, and a one-line snippet of the quoted body.
 *
 * Colors track the bubble direction. An outgoing bubble is a solid
 * accent fill, so the quote reads in the on-accent color
 * ([onAccentColor]); an incoming bubble is a light tint, so the quote
 * uses the quoted sender's own accent ([replyAccentColor]). An
 * [ChatReplyQuote.isUnavailable] target gets a muted, untappable
 * "Message unavailable" placeholder.
 *
 * Mirrors `ChatBubbleCell.applyReplyQuote` from onym-ios PR #174.
 */
@Composable
private fun ReplyQuoteInset(
    reply: ChatReplyQuote,
    replyAccentColor: Color?,
    isOutgoing: Boolean,
    onAccentColor: Color,
    messageId: java.util.UUID,
    onTap: (() -> Unit)?,
) {
    val accent = replyAccentColor ?: MaterialTheme.colorScheme.primary
    val barColor: Color
    val nameColor: Color
    val snippetColor: Color
    val nameText: String
    val snippetText: String
    if (reply.isUnavailable) {
        val muted = if (isOutgoing) {
            onAccentColor.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        barColor = muted
        nameColor = muted
        snippetColor = muted
        nameText = "Message"
        snippetText = "Message unavailable"
    } else {
        barColor = if (isOutgoing) onAccentColor else accent
        nameColor = if (isOutgoing) onAccentColor else accent
        snippetColor = if (isOutgoing) {
            onAccentColor.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        nameText = reply.name
        snippetText = reply.snippet
    }

    val tapModifier = if (onTap != null) {
        Modifier.clickable(onClick = onTap)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .then(tapModifier)
            .testTag("chat_thread.quote.$messageId"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(barColor),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = nameText,
                color = nameColor,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_thread.quote.name.$messageId"),
            )
            Text(
                text = snippetText,
                color = snippetColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_thread.quote.snippet.$messageId"),
            )
        }
    }
}

@Composable
private fun StatusIndicator(
    visual: StatusVisual,
    messageId: java.util.UUID,
) {
    val tint = when (visual.tint) {
        ChatStatusTint.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatStatusTint.Error -> MaterialTheme.colorScheme.error
        ChatStatusTint.Accent -> MaterialTheme.colorScheme.primary
    }
    Icon(
        imageVector = visual.icon,
        contentDescription = visual.contentDescription,
        tint = tint,
        modifier = Modifier
            .size(STATUS_ICON_SIZE)
            .padding(end = 4.dp)
            .testTag("chat_thread.status.$messageId"),
    )
}

/**
 * Pure-data view of the glyph + tint a given [MessageStatus] should
 * surface on an outgoing bubble. Pure function so a unit test can
 * pin the mapping without standing up Compose.
 *
 * Returns `null` for [MessageStatus.RECEIVED] — that combination
 * (outgoing + received) shouldn't occur in practice, but a
 * defensive `null` means the bubble silently skips the indicator
 * rather than rendering a wrong glyph.
 */
internal fun statusVisualFor(status: MessageStatus): StatusVisual? = when (status) {
    MessageStatus.PENDING -> StatusVisual(
        icon = Icons.Outlined.Schedule,
        tint = ChatStatusTint.Muted,
        contentDescription = "Sending",
    )
    MessageStatus.SENT -> StatusVisual(
        icon = Icons.Filled.Check,
        tint = ChatStatusTint.Muted,
        contentDescription = "Sent",
    )
    MessageStatus.DELIVERED -> StatusVisual(
        icon = Icons.Filled.DoneAll,
        tint = ChatStatusTint.Muted,
        contentDescription = "Delivered",
    )
    MessageStatus.READ -> StatusVisual(
        icon = Icons.Filled.DoneAll,
        tint = ChatStatusTint.Accent,
        contentDescription = "Read",
    )
    MessageStatus.FAILED -> StatusVisual(
        icon = Icons.Filled.ErrorOutline,
        tint = ChatStatusTint.Error,
        contentDescription = "Failed — tap to retry",
    )
    MessageStatus.RECEIVED -> null
}

/**
 * Retry-eligibility predicate. Pure function so the unit test can
 * pin the gate without standing up Compose — the production
 * composable consults this exact policy to decide whether to
 * register a tap target on the bubble.
 *
 * Failed outgoing rows are retryable; everything else is a no-op.
 * The interactor's [SendMessageInteractor.retry] enforces the same
 * gate so a stale tap that arrives after a status flip can't
 * double-deliver.
 */
internal fun canRetry(message: ChatMessage): Boolean =
    message.direction == MessageDirection.OUTGOING &&
        message.status == MessageStatus.FAILED

/** Visual metadata for the status glyph that sits below an
 *  outgoing bubble. */
internal data class StatusVisual(
    val icon: ImageVector,
    val tint: ChatStatusTint,
    val contentDescription: String,
)

internal enum class ChatStatusTint { Muted, Error, Accent }

private val BUBBLE_RADIUS = 16.dp
private val STATUS_ICON_SIZE = 14.dp

/** Max rendered width of an image attachment inside a bubble. */
private val IMAGE_MAX_WIDTH = 240.dp

/** Corner radius of the rendered image (slightly tighter than the
 *  bubble so it nests cleanly inside the padding). */
private val IMAGE_RADIUS = 12.dp

/** How far the bubble fill blends toward the surface tint during the
 *  scroll-to-quote highlight pulse. */
private const val HIGHLIGHT_BLEND = 0.25f

/** Drag distance past which releasing arms a reply. */
private val SWIPE_REPLY_THRESHOLD = 56.dp

/** How far the row can travel — past the threshold it resists so the
 *  gesture has a clear "armed" ceiling. */
private val SWIPE_MAX_TRAVEL = 72.dp

/** Incoming-bubble tint opacity over the background. Low enough that
 *  the body text stays readable on top, high enough that the sender's
 *  accent reads at a glance. Mirrors iOS's `incomingTintAlpha` (0.20). */
private const val INCOMING_TINT_ALPHA = 0.20f
