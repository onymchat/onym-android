package chat.onym.android.uitests.screens

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

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
        resetRow().performScrollTo().performClick()
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
