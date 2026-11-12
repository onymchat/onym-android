package app.onym.android.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One onboarding step screen, textless — iOS `OnboardingStepScaffold`
 * parity: a large step [symbol] in place of the title, the content slot,
 * and icon-only controls. The top bar is mirrored (Back on the left, the
 * leave-setup door on the right, the step dots between); the footer holds
 * the step's answers side by side at equal width, so a deferral is a full
 * button beside the primary, never a small link.
 *
 * Every string passed here is an accessibility label: TalkBack reads it,
 * the screen never shows it. [title] (with [hint]) labels the symbol as
 * the screen's heading and carries the `onboarding.<step>.title` tag.
 *
 * Test tags: `onboarding.<step>.{title,primary,skip,back,exit}`.
 */
@Composable
fun OnboardingScaffold(
    step: OnboardingStep,
    title: String,
    /** What the old subtitle said, read after [title]. */
    hint: String? = null,
    /** The step's symbol. null when the content IS the symbol (identity's
     *  key, recovery's life preserver, welcome's door) — the title then
     *  labels the content instead. */
    symbol: ImageVector? = null,
    /** The primary's accessibility label; null hides the primary. */
    primaryLabel: String? = null,
    primaryIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowForward,
    primaryEnabled: Boolean = true,
    onPrimary: () -> Unit,
    onSkip: (() -> Unit)? = null,
    skipLabel: String? = null,
    skipIcon: ImageVector = Icons.Filled.FastForward,
    onBack: (() -> Unit)? = null,
    /** Leaves the walk for the chats (the welcome door's exit). */
    onExit: (() -> Unit)? = null,
    /** Centre symbol + content as one group in the space above the
     *  footer — for steps short enough to have nothing to scroll. */
    centersContent: Boolean = false,
    /** Replaces the button row — the welcome screen's icon row. */
    footer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    indicator: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val label = listOfNotNull(title, hint).joinToString(". ")

    // safeDrawing, applied here rather than by a host Scaffold: the walk
    // renders on RootScreen's early-return path, which never reaches the
    // tab shell's inset-applying Scaffold.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (onBack != null || onExit != null || indicator != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
            ) {
                if (onBack != null) {
                    CornerIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = stringResource(R.string.onboarding_back),
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .testTag("onboarding.${step.tag}.back"),
                    )
                }
                if (indicator != null) {
                    Box(Modifier.align(Alignment.Center)) { indicator() }
                }
                if (onExit != null) {
                    CornerIcon(
                        icon = Icons.Filled.MeetingRoom,
                        label = stringResource(R.string.onboarding_exit_walk),
                        onClick = onExit,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .testTag("onboarding.${step.tag}.exit"),
                    )
                }
            }
        }

        val heading: @Composable () -> Unit = {
            if (symbol != null) {
                Icon(
                    imageVector = symbol,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(64.dp)
                        .semantics {
                            heading()
                            contentDescription = label
                        }
                        .testTag("onboarding.${step.tag}.title"),
                )
            }
        }
        // With no symbol, the title labels the content group instead.
        val labelledContent: @Composable () -> Unit = {
            if (symbol != null) {
                content()
            } else {
                Box(
                    modifier = Modifier
                        .semantics { contentDescription = label }
                        .testTag("onboarding.${step.tag}.title"),
                    contentAlignment = Alignment.Center,
                ) { content() }
            }
        }

        if (centersContent) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                ) {
                    heading()
                    labelledContent()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                heading()
                Spacer(Modifier.height(24.dp))
                labelledContent()
                Spacer(Modifier.height(24.dp))
            }
        }

        if (footer != null) {
            footer()
        } else if (primaryLabel != null || onSkip != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onSkip != null) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .semantics { contentDescription = skipLabel ?: "" }
                            .testTag("onboarding.${step.tag}.skip"),
                    ) { Icon(skipIcon, contentDescription = null, modifier = Modifier.size(24.dp)) }
                }
                if (primaryLabel != null) {
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .semantics { contentDescription = primaryLabel }
                            .testTag("onboarding.${step.tag}.primary"),
                    ) { Icon(primaryIcon, contentDescription = null, modifier = Modifier.size(26.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CornerIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
}
