package app.onym.android.uitests

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.onym.android.moderation.PolicyDocument
import app.onym.android.moderation.PolicyDocumentFetcher
import app.onym.android.moderation.ui.MarkdownDocumentDialog
import org.junit.Rule
import org.junit.Test

/**
 * The in-app policy viewer's one legal invariant: the WHOLE document
 * must be reachable. Twice this regressed by exactly one chrome
 * height — the fillMaxSize body clipped under the title row, then
 * the wrap-content dialog window positioned below the top inset with
 * its bottom off-screen — both times leaving the tail of the legal
 * text composed but unscrollable. This pins the fix: a long document
 * scrolls to its final line, and that line is actually displayed.
 */
class MarkdownDocumentDialogUITest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val LAST_LINE = "END OF LEGAL TEXT MARKER"
    }

    private val longDocument = buildString {
        appendLine("# A long policy")
        appendLine()
        repeat(40) { index ->
            appendLine("Paragraph $index of consequential legal wording that the user")
            appendLine("is entitled to read in full, not merely most of.")
            appendLine()
        }
        appendLine(LAST_LINE)
    }

    private val documents = object : PolicyDocumentFetcher {
        override suspend fun fetch(url: String): PolicyDocument =
            PolicyDocument.Markdown(longDocument)
    }

    @Test
    fun theWholeDocumentIsReachableByScroll() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                MarkdownDocumentDialog(
                    title = "A long policy",
                    url = "https://authority.example/policy/long",
                    documents = documents,
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(LAST_LINE),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // performScrollTo asks the scroll container to bring the node
        // into view; the assertion then demands it is genuinely inside
        // the WINDOW's visible bounds — a node scrolled "into view" of
        // a container whose bottom hangs off-screen fails here, which
        // is exactly both regressions.
        composeRule.onNodeWithText(LAST_LINE, useUnmergedTree = true)
            .performScrollTo()
        assertMarkerFullyOnScreen()

        // Belt and braces: after a raw swipe to the very end, still
        // fully on screen.
        composeRule.onNodeWithTag("moderation.document.scroll", useUnmergedTree = true)
            .performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertMarkerFullyOnScreen()

        // And the chrome stayed functional.
        composeRule.onNodeWithTag("moderation.document.done", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
    }

    /**
     * Asserts in SCREEN coordinates, not window ones: both
     * regressions moved the WINDOW (or the body within it) so that
     * the tail of the document sat below the display while every
     * window-relative bound still looked fine — which is exactly why
     * `assertIsDisplayed` cannot police this. Screen position = the
     * dialog decor's location plus the node's window-relative bounds.
     */
    private fun assertMarkerFullyOnScreen() {
        val node = composeRule.onNodeWithText(LAST_LINE, useUnmergedTree = true)
            .fetchSemanticsNode()
        val composeView = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
        val decorLocation = IntArray(2)
        composeView.rootView.getLocationOnScreen(decorLocation)
        val topOnScreen = decorLocation[1] + node.boundsInWindow.top
        val bottomOnScreen = decorLocation[1] + node.boundsInWindow.bottom
        val displayHeight = composeRule.activity.resources.displayMetrics.heightPixels
        org.junit.Assert.assertTrue(
            "marker clipped: top=$topOnScreen bottom=$bottomOnScreen display=$displayHeight",
            node.boundsInWindow.height > 0 &&
                topOnScreen >= 0f &&
                bottomOnScreen <= displayHeight.toFloat(),
        )
    }
}
