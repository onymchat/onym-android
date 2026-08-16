package app.onym.android.moderation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.onym.android.moderation.PolicyDocument
import app.onym.android.moderation.PolicyDocumentFetcher

/**
 * In-app viewer for a fetched markdown policy document — the Android
 * port of iOS `MarkdownDocumentView`. Links between the authority's
 * own documents stay in-app (same-host https — the viewer's chrome
 * says "this is the authority's document" and shows no address, so
 * rendering another domain inside it would borrow that trust);
 * everything else goes to the system browser, where the address is
 * visible. An HTML answer hands off to the browser instead of
 * pretending to render it.
 */
@Composable
fun MarkdownDocumentDialog(
    title: String,
    url: String,
    documents: PolicyDocumentFetcher,
    onDismiss: () -> Unit,
) {
    // Followed same-host links push; system back pops one document,
    // Done closes from any depth in one tap (iOS parity).
    var stack by remember { mutableStateOf(listOf(title to url)) }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false: with the default, the
        // wrap-content dialog window maxes out at display height but
        // is POSITIONED below the top inset — its bottom hangs
        // off-screen by a status-bar height, and the last sliver of
        // every document is composed yet unreachable by scroll. Laid
        // edge-to-edge (plus the explicit MATCH_PARENT below) the
        // window covers exactly the display and the safeDrawing
        // padding inside takes over the inset job.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The dialog WINDOW cannot be trusted to sit at (0,0): the
        // window manager positions it below the display cutout (and
        // on enforced-edge-to-edge SDKs ignores the cutout-mode
        // attributes), so a full-height layout hangs off the display
        // bottom by exactly the offset — measured +142px on a Pixel
        // 9a — leaving the document's tail composed but unreachable.
        // Instead of fighting per-SDK window flags, MEASURE the
        // overhang after layout and pad the content by exactly that:
        // self-correcting on any device, any API, any future WM
        // behavior. Zero when the window really is full-screen.
        val dialogView = androidx.compose.ui.platform.LocalView.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        var overhangPx by remember { mutableStateOf(0) }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val location = IntArray(2)
                    dialogView.getLocationOnScreen(location)
                    val displayHeight = dialogView.resources.displayMetrics.heightPixels
                    overhangPx =
                        (location[1] + dialogView.height - displayHeight).coerceAtLeast(0)
                }
                .testTag("moderation.document"),
        ) {
            val (currentTitle, currentUrl) = stack.last()
            BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // The measured off-display overhang (see above):
                    // shrinks the layout to the VISIBLE region so the
                    // scroll range covers the whole document.
                    .padding(bottom = with(density) { overhangPx.toDp() }),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("moderation.document.done"),
                    ) { Text(stringResource(R.string.moderation_document_done)) }
                }
                // weight, NOT fillMaxSize: a fillMaxSize child under
                // the title row measures to the FULL dialog height,
                // gets pushed down by the header, and is clipped —
                // the last header-height of the document becomes
                // unreachable by scroll. The weighted slot hands the
                // body exactly the remaining height.
                Box(modifier = Modifier.weight(1f)) {
                    MarkdownDocumentBody(
                        url = currentUrl,
                        rootUrl = url,
                        documents = documents,
                        onFollowLink = { destination ->
                            stack = stack +
                                (MarkdownBlocks.impliedTitle(destination) to destination)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownDocumentBody(
    url: String,
    rootUrl: String,
    documents: PolicyDocumentFetcher,
    onFollowLink: (String) -> Unit,
) {
    var phase by remember(url) { mutableStateOf<DocumentPhase>(DocumentPhase.Loading) }
    LaunchedEffect(url) {
        phase = try {
            when (val document = documents.fetch(url.substringBefore('#'))) {
                is PolicyDocument.Markdown ->
                    DocumentPhase.Document(MarkdownBlocks.parse(document.text))
                PolicyDocument.WebPage -> DocumentPhase.WebPage
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DocumentPhase.Failed
        }
    }

    when (val current = phase) {
        DocumentPhase.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        is DocumentPhase.Document -> {
            val uriHandler = LocalUriHandler.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp)
                    .testTag("moderation.document.scroll"),
            ) {
                current.blocks.forEach { block ->
                    MarkdownBlockView(block) { href ->
                        val resolved = MarkdownBlocks.resolveLink(href, url)
                        if (MarkdownBlocks.followsInApp(resolved, rootUrl)) {
                            onFollowLink(resolved)
                        } else {
                            runCatching { uriHandler.openUri(resolved) }
                        }
                    }
                }
            }
        }

        DocumentPhase.WebPage -> HandOff(
            message = stringResource(R.string.moderation_document_web_page),
            url = url,
        )

        DocumentPhase.Failed -> HandOff(
            message = stringResource(R.string.moderation_document_failed),
            url = url,
        )
    }
}

private sealed interface DocumentPhase {
    data object Loading : DocumentPhase
    data class Document(val blocks: List<MdBlock>) : DocumentPhase
    data object WebPage : DocumentPhase
    data object Failed : DocumentPhase
}

@Composable
private fun HandOff(message: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { runCatching { uriHandler.openUri(url) } },
            modifier = Modifier.testTag("moderation.document.open_in_browser"),
        ) { Text(stringResource(R.string.moderation_document_open_browser)) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock, onLink: (String) -> Unit) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = annotated(block.inlines, onLink),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            modifier = Modifier.padding(top = if (block.level <= 2) 16.dp else 10.dp, bottom = 4.dp),
        )

        is MdBlock.Paragraph -> Text(
            text = annotated(block.inlines, onLink),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is MdBlock.ListItem -> Row(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                text = block.ordinal?.let { "$it." } ?: "•",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = annotated(block.inlines, onLink),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is MdBlock.Quote -> Row(modifier = Modifier.padding(vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Text(
                text = annotated(block.inlines, onLink),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        is MdBlock.CodeBlock -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        )

        MdBlock.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        is MdBlock.Table -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        ) {
            block.rows.forEach { row ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    row.forEachIndexed { index, cell ->
                        Text(
                            text = annotated(cell, onLink),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == 0) FontWeight.SemiBold else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun annotated(inlines: List<MdInline>, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        val linkColor = MaterialTheme.colorScheme.primary
        inlines.forEach { inline ->
            when (inline) {
                is MdInline.Text -> append(inline.text)
                is MdInline.Bold ->
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline.text) }
                is MdInline.Italic ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.text) }
                is MdInline.Code ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(inline.text) }
                is MdInline.Link -> withLink(
                    LinkAnnotation.Clickable(
                        tag = inline.url,
                        styles = androidx.compose.ui.text.TextLinkStyles(
                            style = SpanStyle(color = linkColor),
                        ),
                    ) { onLink(inline.url) },
                ) { append(inline.text) }
            }
        }
    }
