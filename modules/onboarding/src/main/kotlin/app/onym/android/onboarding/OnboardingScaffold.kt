package app.onym.android.onboarding

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One onboarding step screen, per the established consent-screen
 * skeleton (see `ModuleConsentScreen`): scrollable large title +
 * content slot above a pinned action bar with the primary button and
 * text Back/Skip, the step indicator injected as a composable slot.
 *
 * Test tags follow `onboarding.<step>.<element>`:
 * `onboarding.<step>.primary`, `onboarding.<step>.skip`,
 * `onboarding.<step>.back`, `onboarding.<step>.title`.
 */
@Composable
fun OnboardingScaffold(
    step: OnboardingStep,
    title: String,
    subtitle: String? = null,
    primaryTitle: String,
    /**
     * Disables the primary button — mandatory steps with no recorded
     * outcome render Continue disabled (the flow's `advance()` guard
     * is the second layer of the same rule).
     */
    primaryEnabled: Boolean = true,
    onPrimary: () -> Unit,
    /** null hides the Skip affordance (unskippable steps). */
    onSkip: (() -> Unit)? = null,
    /** The Skip affordance's label — the recovery step's reads
     *  "Remind me later" (a deferral, not a skip). Ignored when
     *  [onSkip] is null. */
    skipTitle: String? = null,
    /** null hides the Back affordance (the first step). */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Step indicator slot, rendered centered above the title. null
     *  on the unnumbered steps — no blank indicator band is
     *  reserved for them. */
    indicator: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // safeDrawing, applied here rather than by a host Scaffold: the
    // onboarding walk renders on RootScreen's early-return path, which
    // never reaches the tab shell's inset-applying Scaffold — without
    // this the title draws under the status bar and the pinned action
    // bar under the gesture-navigation bar (edge-to-edge is on for
    // every SDK via enableEdgeToEdge). Covers cutouts and landscape
    // side bars too, not just top/bottom.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // The content slot renders edge-to-edge: the Settings atoms
        // (SettingsCard / SettingsFootnote) and the PR 3 app surfaces
        // carry their own 16dp inset, so only the header text gets
        // the screen padding here.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (indicator != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { indicator() }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = if (indicator != null) 16.dp else 24.dp)
                    .testTag("onboarding.${step.tag}.title"),
            )

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding.${step.tag}.primary"),
            ) {
                Text(primaryTitle, style = MaterialTheme.typography.titleMedium)
            }

            // Back and Skip anchor opposite edges when both exist; a
            // lone affordance centers under the primary button instead
            // of hugging one edge of an otherwise empty row.
            val loneAffordance = (onBack == null) != (onSkip == null)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement =
                    if (loneAffordance) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("onboarding.${step.tag}.back"),
                    ) { Text(stringResource(R.string.onboarding_back)) }
                }
                if (onSkip != null) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.testTag("onboarding.${step.tag}.skip"),
                    ) { Text(skipTitle ?: stringResource(R.string.onboarding_skip)) }
                }
            }
        }
    }
}
