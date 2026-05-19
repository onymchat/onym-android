package app.onym.android.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
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
) {
    var text by remember { mutableStateOf("") }
    val sendBody = trimmedSendBody(text)
    val canSend = enabled && sendBody != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("chat_thread.input_panel"),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(BUBBLE_CORNER))
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
                val body = trimmedSendBody(text) ?: return@FilledIconButton
                onSend(body)
                text = ""
            },
            enabled = canSend,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
