package app.onym.android.chats

import app.onym.android.UITestRegistry
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onym.android.group.MemberProfile
import app.onym.android.group.OnymAccent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Compose chat-thread screen. Tapping a group in the Chats tab
 * opens this destination; the members list is one tap deeper behind
 * the info action in the top bar.
 *
 * Read-only message scrollback lands in this PR: bubble-rendered
 * messages stream from [ChatThreadViewModel.messages] through a
 * `LazyColumn` keyed by message id, with cold-open scroll-to-bottom
 * and only-when-near-bottom auto-scroll on new arrivals so reading
 * older messages doesn't get hijacked. Input panel + send button
 * stays a placeholder — that's the next PR.
 *
 * Mirrors `ChatThreadView.swift` from onym-ios PR #152. iOS uses a
 * UIKit controller + `UITableViewDiffableDataSource`; Compose's
 * `LazyColumn(items, key = ...)` covers the same data-diff
 * concerns. The "scroll on cold open + only when near bottom"
 * heuristic mirrors the iOS controller's logic in
 * [shouldAutoScrollOnAppend].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(
    viewModel: ChatThreadViewModel,
    onBack: () -> Unit,
    onShowMembers: () -> Unit,
    /** When non-null (opened from Search), the thread cold-opens scrolled
     *  to this message and flashes it, instead of opening at the bottom. */
    scrollToMessageId: java.util.UUID? = null,
) {
    val group by viewModel.group.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
    val pendingMedia by viewModel.pendingMedia.collectAsStateWithLifecycle()

    // The video attachment currently shown in the full-screen player, if any.
    var playingVideo by remember { mutableStateOf<ChatVideoAttachment?>(null) }
    // The image attachment currently shown in the full-screen viewer, if any.
    var viewingImage by remember { mutableStateOf<ChatImageAttachment?>(null) }
    // The album currently open in the swipeable full-screen gallery, if any.
    var galleryContext by remember { mutableStateOf<AlbumGalleryContext?>(null) }
    // The failed outgoing media message whose Resend/Delete menu is open.
    var failedMenuMessageId by remember { mutableStateOf<java.util.UUID?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Two-step media send: the picker STAGES items into the composer's
    // preview strip; the actual upload only fires on Send. Multi-select
    // so several photos/videos batch into one album.
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        for (uri in uris) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: continue
            viewModel.stagePendingMedia(
                thumbnail = decodeThumbnail(bytes),
                source = ChatMediaSource.Image(bytes),
            )
        }
    }
    // Under the UI-test harness the system photo picker can't be driven,
    // so stage a generated test image directly instead.
    val onAttach: () -> Unit = {
        if (UITestRegistry.enabled) {
            val bytes = debugTestImageBytes()
            viewModel.stagePendingMedia(
                thumbnail = decodeThumbnail(bytes),
                source = ChatMediaSource.Image(bytes),
            )
        } else {
            imagePicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
    }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        for (uri in uris) {
            viewModel.stagePendingMedia(
                thumbnail = videoThumbnail(context, uri),
                source = ChatMediaSource.Video(uri),
            )
        }
    }
    // Under the UI-test harness the picker + Media3 transcoding can't run,
    // so stage a canned video (the interactor's injected encoder ignores
    // the URI) straight through the staging pipeline.
    val onAttachVideo: () -> Unit = {
        if (UITestRegistry.enabled) {
            viewModel.stagePendingMedia(
                thumbnail = null,
                source = ChatMediaSource.Video(android.net.Uri.EMPTY),
            )
        } else {
            videoPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ChatThreadTitle(
                        name = group?.name ?: "Chat",
                        memberCount = group?.memberProfiles?.size ?: 0,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_thread.back_button"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onShowMembers,
                        modifier = Modifier.testTag("chat_thread.info_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Members",
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (group == null) {
            MissingGroup(padding = padding)
        } else {
            ChatThreadBody(
                messages = messages,
                // Member profiles flow from the same live `group`
                // snapshot the title reads, so a joiner landing or an
                // alias edit repaints the rendered name headers without
                // a fresh message arriving (requirement #6) — Compose
                // recomposes the `remember(memberProfiles)` below.
                memberProfiles = group?.memberProfiles.orEmpty(),
                padding = padding,
                onSend = viewModel::send,
                onRetry = viewModel::retry,
                onAttach = onAttach,
                onAttachVideo = onAttachVideo,
                imageLoader = viewModel.imageLoader,
                onImageTapped = { viewingImage = it },
                onVideoTapped = { playingVideo = it },
                onAlbumTapped = { media, index -> galleryContext = AlbumGalleryContext(media, index) },
                onFailedMediaTap = { failedMenuMessageId = it },
                pendingMedia = pendingMedia,
                onRemovePending = viewModel::removePendingMedia,
                onSendMedia = viewModel::sendPendingMedia,
                scrollToMessageId = scrollToMessageId,
                replyingTo = replyingTo,
                onArmReply = viewModel::armReply,
                onCancelReply = viewModel::cancelReply,
            )
        }
    }

    // Full-screen video player, shown over everything when a video
    // bubble is tapped. Lazily downloads + decrypts the clip.
    playingVideo?.let { attachment ->
        FullScreenVideoPlayer(
            attachment = attachment,
            videoLoader = viewModel.videoLoader,
            onDismiss = { playingVideo = null },
        )
    }

    // Full-screen image viewer, shown when an image bubble is tapped.
    // Swipe down to dismiss.
    viewingImage?.let { attachment ->
        FullScreenImageViewer(
            attachment = attachment,
            imageLoader = viewModel.imageLoader,
            onDismiss = { viewingImage = null },
        )
    }

    // Swipeable full-screen album gallery, shown when an album tile is
    // tapped. Pages through the album's images/videos starting at the
    // tapped index.
    galleryContext?.let { ctx ->
        FullScreenAlbumGallery(
            media = ctx.media,
            startIndex = ctx.startIndex,
            imageLoader = viewModel.imageLoader,
            videoLoader = viewModel.videoLoader,
            onDismiss = { galleryContext = null },
        )
    }

    // Resend / Delete menu for a failed outgoing media message.
    failedMenuMessageId?.let { messageId ->
        FailedMediaActionSheet(
            onResend = {
                viewModel.retry(messageId)
                failedMenuMessageId = null
            },
            onDelete = {
                viewModel.deleteMessage(messageId)
                failedMenuMessageId = null
            },
            onDismiss = { failedMenuMessageId = null },
        )
    }
}

/** The album + start index backing the full-screen gallery. Mirrors iOS
 *  `AlbumGalleryContext`. */
private data class AlbumGalleryContext(
    val media: List<ChatMediaAttachment>,
    val startIndex: Int,
)

@Composable
private fun ChatThreadBody(
    messages: List<ChatMessage>,
    memberProfiles: Map<String, MemberProfile>,
    padding: PaddingValues,
    onSend: (String) -> Unit,
    onRetry: (java.util.UUID) -> Unit,
    onAttach: (() -> Unit)? = null,
    onAttachVideo: (() -> Unit)? = null,
    imageLoader: ChatImageLoader? = null,
    onImageTapped: ((ChatImageAttachment) -> Unit)? = null,
    onVideoTapped: ((ChatVideoAttachment) -> Unit)? = null,
    onAlbumTapped: ((List<ChatMediaAttachment>, Int) -> Unit)? = null,
    onFailedMediaTap: ((java.util.UUID) -> Unit)? = null,
    pendingMedia: List<PendingMediaItem> = emptyList(),
    onRemovePending: ((java.util.UUID) -> Unit)? = null,
    onSendMedia: (() -> Unit)? = null,
    scrollToMessageId: java.util.UUID? = null,
    replyingTo: java.util.UUID?,
    onArmReply: (java.util.UUID) -> Unit,
    onCancelReply: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Defensive sort. The repository's contract is ascending by
    // sentAtMillis, but a future caller / test stub regressing
    // the order shouldn't surface as a visibly-misordered chat.
    // Mirrors the iOS PR #152 fixup that added the same guard at
    // the controller boundary.
    val sortedMessages = remember(messages) {
        messages.sortedBy { it.sentAtMillis }
    }
    // Resolve per-message sender presentation (name + accent + whether
    // to show the run-start header) off the sorted order + the group's
    // member profiles. Recomputes when either changes — including a
    // live profile update — so headers repaint in place.
    val senderDisplays = remember(sortedMessages, memberProfiles) {
        buildSenderDisplays(sortedMessages, memberProfiles)
    }
    // Per-message reply quote, resolved live from the loaded list (a
    // ref to a message we don't have → "Message unavailable"). Plus an
    // id→index map so tapping a quote can scroll to the original.
    val replyQuotes = remember(sortedMessages, memberProfiles) {
        buildReplyQuotes(sortedMessages, memberProfiles)
    }
    val indexById = remember(sortedMessages) {
        sortedMessages.withIndex().associate { (index, message) -> message.id to index }
    }

    // Tapping a quote scrolls the replied-to message into view and
    // briefly flashes it. No-op if the target isn't in the current
    // snapshot (pruned, or never arrived). Mirrors
    // `ChatThreadViewController.scrollAndHighlight` from onym-ios #174.
    val coroutineScope = rememberCoroutineScope()
    var highlightedId by remember { mutableStateOf<java.util.UUID?>(null) }
    val scrollToAndHighlight: (java.util.UUID) -> Unit = remember(indexById) {
        target@{ targetId ->
            val index = indexById[targetId] ?: return@target
            coroutineScope.launch {
                listState.animateScrollToItem(index)
                highlightedId = targetId
                kotlinx.coroutines.delay(HIGHLIGHT_HOLD_MILLIS)
                if (highlightedId == targetId) highlightedId = null
            }
        }
    }

    // Auto-scroll behavior:
    //   - cold open: jump to the latest message (no animation).
    //   - subsequent appends: only scroll if the user was already
    //     near the bottom (otherwise reading older messages would
    //     get hijacked).
    var hasInitialScrolled by remember { mutableStateOf(false) }
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            isNearBottom(
                totalItems = total,
                lastVisibleIndex = lastVisibleIndex,
            )
        }
    }
    LaunchedEffect(sortedMessages.size) {
        if (sortedMessages.isEmpty()) return@LaunchedEffect
        val lastIndex = sortedMessages.lastIndex
        if (!hasInitialScrolled) {
            // Opened-from-search: land on the target message + flash it,
            // rather than jumping to the bottom.
            val targetIndex = scrollToMessageId?.let { indexById[it] }
            if (targetIndex != null) {
                listState.scrollToItem(targetIndex)
                highlightedId = scrollToMessageId
                launch {
                    kotlinx.coroutines.delay(HIGHLIGHT_HOLD_MILLIS)
                    if (highlightedId == scrollToMessageId) highlightedId = null
                }
            } else {
                listState.scrollToItem(lastIndex)
            }
            hasInitialScrolled = true
            return@LaunchedEffect
        }
        if (nearBottom) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // Keyboard-rise re-anchor. `imePadding()` below shrinks this
    // column frame-by-frame as the soft keyboard animates in, and a
    // LazyColumn keeps its top pinned — so the bottom messages slide
    // out of view behind the input panel. A single scroll when the
    // keyboard *starts* showing gets re-hidden by the rest of the
    // shrink, so we re-pin on every rising frame of the IME inset
    // instead, gluing the latest message just above the panel for
    // the whole animation.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // The pin decision must reflect where the user was *before* the
    // keyboard began shrinking the viewport. Capture `nearBottom`
    // while the IME is down; it freezes at that value the moment the
    // keyboard starts rising. Reading it live at the rising edge
    // would race the relayout and misfire on instant (non-animated)
    // keyboards.
    var anchoredBeforeIme by remember { mutableStateOf(true) }
    LaunchedEffect(nearBottom, imeBottom) {
        if (imeBottom == 0) anchoredBeforeIme = nearBottom
    }

    // Only rising frames re-pin. Falling frames (keyboard dismissing)
    // are left alone, so a user who scrolled up to read history while
    // typing isn't yanked back to the bottom when they close the
    // keyboard. Instant scroll (not animated) so the bottom tracks
    // the rising panel smoothly rather than lagging behind it.
    var prevImeBottom by remember { mutableStateOf(0) }
    LaunchedEffect(imeBottom) {
        val rising = imeBottom > prevImeBottom
        prevImeBottom = imeBottom
        if (shouldGlueToBottomOnImeRise(
                rising = rising,
                anchoredBeforeIme = anchoredBeforeIme,
                hasMessages = sortedMessages.isNotEmpty(),
            )
        ) {
            listState.scrollToItem(sortedMessages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            // Consume whatever bottom inset this screen's own Scaffold
            // already applied before `imePadding()` reads the IME
            // inset. The IME inset is measured from the screen bottom
            // and overlaps the navigation-bar band, so without the
            // consume `imePadding()` would re-add an inset that's
            // already in `padding` and leave a gap above the keyboard.
            // (The cross-Scaffold nav-bar double-count from #154 is
            // handled upstream by `consumeWindowInsets` on RootScreen's
            // NavHost; this keeps the panel flush even if the screen is
            // ever hosted without that outer consume — e.g. in a test.)
            .consumeWindowInsets(padding)
            .imePadding()  // slides the input panel above the soft keyboard
            .testTag("chat_thread.body"),
    ) {
        if (sortedMessages.isEmpty()) {
            EmptyThread(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("chat_thread.message_list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // key = { it.id } keeps slot identity stable across
                // recompositions; status flips re-render the bubble
                // body because ChatMessage's equals() compares all
                // fields. The iOS twin needed an explicit
                // diffable-identity widen via reconfigureItems for
                // the same property — Compose gives it for free.
                items(
                    items = sortedMessages,
                    key = { it.id },
                ) { message ->
                    ChatBubble(
                        message = message,
                        sender = senderDisplays[message.id] ?: ChatSenderDisplay.Unknown,
                        onRetry = { onRetry(message.id) },
                        imageLoader = imageLoader,
                        onImageTapped = onImageTapped,
                        onVideoTapped = onVideoTapped,
                        onAlbumItemTapped = onAlbumTapped?.let { cb ->
                            { index -> cb(message.media, index) }
                        },
                        onFailedMediaTap = onFailedMediaTap?.let { cb ->
                            { cb(message.id) }
                        },
                        reply = replyQuotes[message.id],
                        onQuoteTap = message.replyToMessageId?.let { targetId ->
                            { scrollToAndHighlight(targetId) }
                        },
                        isHighlighted = message.id == highlightedId,
                        onSwipeReply = { onArmReply(message.id) },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp)
        // Reply banner sits between the message list and the composer
        // while a reply is armed. Resolved live from the loaded
        // messages; if the target vanished, the banner just doesn't show.
        val replyTarget = replyingTo?.let { id -> sortedMessages.firstOrNull { it.id == id } }
        val composerFocus = remember { FocusRequester() }
        if (replyTarget != null) {
            ChatReplyBanner(
                name = senderName(replyTarget.senderBlsPubkeyHex, memberProfiles),
                snippet = replyTarget.body,
                accent = OnymAccent.forSender(replyTarget.senderBlsPubkeyHex)
                    .color(isSystemInDarkTheme()),
                onCancel = onCancelReply,
            )
        }
        // Raise the keyboard + focus the composer the moment a reply
        // is armed, so the user can type straight away. iOS calls
        // `focusComposer()` from `armReply`.
        LaunchedEffect(replyingTo) {
            if (replyingTo != null) {
                runCatching { composerFocus.requestFocus() }
            }
        }
        // The VM's send does the trim guard + optimistic insert +
        // status flip through SendMessageInteractor, and threads the
        // armed reply target so the sent message renders its quote.
        // SendMessageError routes into viewModel.lastSendError; network
        // / fan-out failures land as MessageStatus.FAILED on the bubble.
        ChatInputPanel(
            onSend = onSend,
            focusRequester = composerFocus,
            onAttach = onAttach,
            onAttachVideo = onAttachVideo,
            pendingMedia = pendingMedia,
            onRemovePending = onRemovePending,
            onSendMedia = onSendMedia,
        )
    }
}

/**
 * Heuristic for "is the user effectively scrolled to the bottom"
 * — true when the last visible item index is within the threshold
 * of the last item, or when the list is empty. Pure function so a
 * unit test exercises the policy without standing up a
 * `LazyListState` (which requires Compose's compositor).
 *
 * Wire-equivalent to the iOS controller's
 * `contentOffset.y + frame.height >= contentSize.height - 100`
 * check — same threshold semantics, expressed in item indices
 * since Compose's LazyListState doesn't expose pixel offsets at
 * the public API.
 */
internal fun isNearBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    nearBottomThreshold: Int = NEAR_BOTTOM_INDEX_THRESHOLD,
): Boolean {
    if (totalItems == 0) return true
    if (lastVisibleIndex < 0) return false
    return lastVisibleIndex >= totalItems - nearBottomThreshold
}

/** Items-from-bottom window that counts as "near the bottom" for
 *  the auto-scroll heuristic. 2 = the user is reading the last or
 *  second-to-last message. */
internal const val NEAR_BOTTOM_INDEX_THRESHOLD = 2

/** How long a quote-tapped message stays highlighted after the scroll
 *  settles before the pulse fades. */
private const val HIGHLIGHT_HOLD_MILLIS = 900L

/**
 * Generates a small solid-color JPEG for the UI-test harness, which
 * can't drive the system photo picker. Mirrors iOS
 * `debugTestImageData()`. Only ever called under [UITestRegistry.enabled].
 */
private fun debugTestImageBytes(): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(
        240,
        160,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    bitmap.eraseColor(android.graphics.Color.rgb(0x3D, 0x8B, 0xFD))
    val stream = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
    return stream.toByteArray()
}

/**
 * Full-screen video player shown over the thread when a video bubble is
 * tapped. Lazily downloads + decrypts the clip via [videoLoader], then
 * plays the local file with an ExoPlayer surface. Mirrors iOS's
 * `FullScreenVideoView`.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullScreenVideoPlayer(
    attachment: ChatVideoAttachment,
    videoLoader: ChatVideoLoader?,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var file by remember(attachment.sha256) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(attachment.sha256) {
        file = videoLoader?.file(attachment)
    }

    val scope = rememberCoroutineScope()
    val offsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val fadeDistancePx = with(density) { 300.dp.toPx() }
    val progress = (offsetY.value / fadeDistancePx).coerceIn(0f, 1f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - progress * 0.7f))
                // Swipe down to dismiss — no close button (matches the
                // image viewer). Media3's PlayerView consumes touches for
                // its controls, so claim the vertical drag on the Initial
                // pointer pass (before the player view) once it's clearly
                // downward, leaving taps + scrubbing to the controls.
                .swipeDownToDismiss(offsetY, scope, dismissThresholdPx, onDismiss)
                .testTag("chat_thread.video_player"),
            contentAlignment = Alignment.Center,
        ) {
            val currentFile = file
            if (currentFile != null) {
                val exoPlayer = remember(currentFile) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(currentFile)))
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(exoPlayer) {
                    onDispose { exoPlayer.release() }
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { androidx.compose.ui.unit.IntOffset(0, offsetY.value.roundToInt()) },
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

/**
 * Swipe-down-to-dismiss for a Box that hosts a touch-consuming child
 * (e.g. a Media3 `PlayerView`). Claims the gesture on the **Initial**
 * pointer pass — before the child — but only once the drag is clearly
 * downward past the touch slop, then consumes the moves so the child
 * never sees them. Taps and other gestures fall through to the child, so
 * playback controls keep working. Releasing past [thresholdPx] dismisses;
 * otherwise the content springs back to [offsetY] == 0.
 */
private fun Modifier.swipeDownToDismiss(
    offsetY: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    thresholdPx: Float,
    onDismiss: () -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
        )
        val startY = down.position.y
        var claimed = false
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!claimed && change.position.y - startY > slop) claimed = true
            if (claimed) {
                val delta = change.positionChange().y
                change.consume()
                scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
            }
        }
        if (claimed) {
            if (offsetY.value > thresholdPx) onDismiss()
            else scope.launch { offsetY.animateTo(0f) }
        }
    }
}

/**
 * Full-screen image viewer shown over the thread when an image bubble is
 * tapped. Lazily downloads + decrypts the image; the image tracks a
 * vertical drag and the black backdrop fades with it, releasing past a
 * downward threshold dismisses (otherwise it springs back). Mirrors
 * iOS's swipe-down-to-dismiss `FullScreenImageView`.
 */
@Composable
private fun FullScreenImageViewer(
    attachment: ChatImageAttachment,
    imageLoader: ChatImageLoader?,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(attachment.sha256) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(attachment.sha256) { bitmap = imageLoader?.load(attachment) }

    val scope = rememberCoroutineScope()
    val offsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val fadeDistancePx = with(density) { 300.dp.toPx() }
    val progress = (kotlin.math.abs(offsetY.value) / fadeDistancePx).coerceIn(0f, 1f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - progress * 0.7f))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (offsetY.value > dismissThresholdPx) {
                                onDismiss()
                            } else {
                                scope.launch { offsetY.animateTo(0f) }
                            }
                        },
                        onDragCancel = { scope.launch { offsetY.animateTo(0f) } },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetY.snapTo(offsetY.value + dragAmount) }
                    }
                }
                .testTag("chat_thread.image_viewer"),
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            if (shown != null) {
                androidx.compose.foundation.Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { androidx.compose.ui.unit.IntOffset(0, offsetY.value.roundToInt()) },
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

/**
 * Decides whether a frame of the IME-rise animation should re-pin the
 * message list to the latest message. True only on a *rising* frame
 * (the keyboard is growing, not dismissing), when the list is
 * non-empty, and when the user was at the bottom before the keyboard
 * started — so reading older history while typing isn't yanked down,
 * and dismissing the keyboard never drags a scrolled-up user back.
 * Pure function so the policy is unit-tested without standing up a
 * `LazyListState` or driving a real soft keyboard; the composable
 * feeds it the rising-edge test on `WindowInsets.ime` and the
 * captured pre-keyboard `isNearBottom` state.
 */
internal fun shouldGlueToBottomOnImeRise(
    rising: Boolean,
    anchoredBeforeIme: Boolean,
    hasMessages: Boolean,
): Boolean = rising && anchoredBeforeIme && hasMessages

/**
 * Swipeable full-screen album gallery. Pages horizontally through an
 * album's images/videos starting at the tapped index; a vertical drag
 * fades the backdrop and dismisses past a threshold. Mirrors iOS's
 * `FullScreenGalleryView` (paged TabView + AVKit).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullScreenAlbumGallery(
    media: List<ChatMediaAttachment>,
    startIndex: Int,
    imageLoader: ChatImageLoader?,
    videoLoader: ChatVideoLoader?,
    onDismiss: () -> Unit,
) {
    if (media.isEmpty()) return
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, media.lastIndex),
        pageCount = { media.size },
    )
    val scope = rememberCoroutineScope()
    val offsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val fadeDistancePx = with(density) { 300.dp.toPx() }
    val progress = (kotlin.math.abs(offsetY.value) / fadeDistancePx).coerceIn(0f, 1f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - progress * 0.7f))
                .testTag("chat_thread.album_gallery"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { androidx.compose.ui.unit.IntOffset(0, offsetY.value.roundToInt()) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (offsetY.value > dismissThresholdPx) onDismiss()
                                else scope.launch { offsetY.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { offsetY.animateTo(0f) } },
                        ) { change, dragAmount ->
                            change.consume()
                            scope.launch { offsetY.snapTo(offsetY.value + dragAmount) }
                        }
                    },
            ) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val item = media[page]
                    val videoItem = item.video
                    val imageItem = item.image
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.isVideo && videoItem != null) {
                            GalleryVideoPage(videoItem, videoLoader)
                        } else if (imageItem != null) {
                            GalleryImagePage(imageItem, imageLoader)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryImagePage(
    attachment: ChatImageAttachment,
    imageLoader: ChatImageLoader?,
) {
    var bitmap by remember(attachment.sha256) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(attachment.sha256) { bitmap = imageLoader?.load(attachment) }
    val shown = bitmap
    if (shown != null) {
        androidx.compose.foundation.Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = "Photo",
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        CircularProgressIndicator(color = Color.White)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun GalleryVideoPage(
    attachment: ChatVideoAttachment,
    videoLoader: ChatVideoLoader?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var file by remember(attachment.sha256) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(attachment.sha256) { file = videoLoader?.file(attachment) }
    val currentFile = file
    if (currentFile != null) {
        val exoPlayer = remember(currentFile) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(currentFile)))
                prepare()
            }
        }
        DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        CircularProgressIndicator(color = Color.White)
    }
}

/**
 * Resend / Delete chooser for a failed outgoing media message. Mirrors
 * the iOS confirmation dialog wired to a tap on failed media.
 */
@Composable
private fun FailedMediaActionSheet(
    onResend: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message failed to send") },
        text = { Text("Resend or delete this media?") },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onResend,
                modifier = Modifier.testTag("chat_thread.failed_media.resend"),
            ) { Text("Resend") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("chat_thread.failed_media.delete"),
            ) { Text("Delete") }
        },
        modifier = Modifier.testTag("chat_thread.failed_media.sheet"),
    )
}

/** Decodes a downsampled thumbnail for the composer preview strip. */
private fun decodeThumbnail(bytes: ByteArray, maxDim: Int = 256): android.graphics.Bitmap? =
    runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val larger = maxOf(bounds.outWidth, bounds.outHeight)
        while (larger / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

/** Extracts a poster frame from a picked video URI for the preview strip. */
private fun videoThumbnail(
    context: android.content.Context,
    uri: android.net.Uri,
): android.graphics.Bitmap? =
    runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } finally {
            retriever.release()
        }
    }.getOrNull()

@Composable
private fun EmptyThread(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("chat_thread.empty"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No messages yet. Say hi.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two-line nav title: group name on top, "N members" subtitle
 * below. Subtitle is hidden when [memberCount] is `<= 1` — singleton
 * groups (just the creator) and not-yet-loaded groups would render
 * an awkward "0 members" / "1 member" otherwise. Same hide-gate as
 * iOS PR #156.
 */
@Composable
private fun ChatThreadTitle(
    name: String,
    memberCount: Int,
) {
    val subtitle = memberCountSubtitle(memberCount)
    Column(
        modifier = Modifier.testTag("chat_thread.title"),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chat_thread.title_subtitle"),
            )
        }
    }
}

/**
 * Subtitle string for the chat-thread nav. Pluralized as "N members"
 * because the `<= 1` gate guarantees we never render the singular
 * form. Pure function so the unit test pins the gate + format
 * without standing up Compose.
 *
 * Mirrors `subtitle(forMemberCount:)` from onym-ios PR #156.
 */
internal fun memberCountSubtitle(memberCount: Int): String? = when {
    memberCount <= 1 -> null
    else -> "$memberCount members"
}

@Composable
private fun MissingGroup(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("chat_thread.missing"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "This chat isn't on this device.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
