package chat.onym.android.uitests.screens

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode

/**
 * Page object for the Anchors drill-down. Three layers:
 * Anchors root → Network → Version.
 */
class AnchorsScreenObjects(private val rule: ComposeContentTestRule) {

    fun networkRow(network: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("anchors.network.$network")

    fun typeRow(type: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("anchors.type.$type")

    fun versionRow(release: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("anchors.version.$release")

    fun resetRow(): SemanticsNodeInteraction =
        rule.onNodeWithTag("anchors.version.reset")

    fun tapNetwork(network: String): AnchorsScreenObjects {
        networkRow(network).performScrollTo().performClick()
        return this
    }

    fun tapType(type: String): AnchorsScreenObjects {
        typeRow(type).performScrollTo().performClick()
        return this
    }

    fun tapVersion(release: String): AnchorsScreenObjects {
        versionRow(release).performScrollTo().performClick()
        return this
    }

    fun tapReset(): AnchorsScreenObjects {
        // The reset row sits at the bottom of `AnchorsVersionScreen`,
        // past the releases list, the custom-action rows, and the
        // section header. On a CI emulator the row is below the
        // initial viewport and the LazyColumn never realises its
        // semantics, so a plain `performScrollTo()` (which only
        // scrolls when the node is already in the tree) fails with
        // "could not find any node". Scroll the parent list to the
        // matcher first so the row materialises.
        rule.onNodeWithTag("anchors.version.list")
            .performScrollToNode(hasTestTag("anchors.version.reset"))
        resetRow().performClick()
        return this
    }

    fun assertNetworkRowVisible(network: String): AnchorsScreenObjects {
        networkRow(network).performScrollTo().assertIsDisplayed()
        return this
    }

    fun assertTypeRowVisible(type: String): AnchorsScreenObjects {
        typeRow(type).performScrollTo().assertIsDisplayed()
        return this
    }
}
