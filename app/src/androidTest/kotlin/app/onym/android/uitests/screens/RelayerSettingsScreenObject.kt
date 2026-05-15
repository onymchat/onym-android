package app.onym.android.uitests.screens

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft

/**
 * Page object for the Relayer settings screen. Wraps the test-tag
 * vocabulary the production composables expose
 * (`relayer.configured.<url>`, `relayer.add.custom.field`, etc.)
 * so test bodies stay readable even when individual tags drift.
 */
class RelayerSettingsScreenObject(private val rule: ComposeContentTestRule) {

    fun configuredRow(url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("relayer.configured.$url")

    fun primaryButton(url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("relayer.configured.$url.primary_button")

    fun customField(): SemanticsNodeInteraction = rule.onNodeWithTag("relayer.add.custom.field")
    fun customAddButton(): SemanticsNodeInteraction = rule.onNodeWithTag("relayer.add.custom.button")

    fun strategyButton(strategy: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("relayer.strategy.${strategy.lowercase()}")

    fun knownAddRow(url: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("relayer.add.known.$url")

    fun assertConfigured(url: String): RelayerSettingsScreenObject {
        configuredRow(url).performScrollTo().assertIsDisplayed()
        return this
    }

    fun tapMarkPrimary(url: String): RelayerSettingsScreenObject {
        primaryButton(url).performScrollTo().performClick()
        return this
    }

    fun tapStrategy(strategy: String): RelayerSettingsScreenObject {
        strategyButton(strategy).performScrollTo().performClick()
        return this
    }

    fun swipeDelete(url: String): RelayerSettingsScreenObject {
        configuredRow(url).performScrollTo().performTouchInputSwipeLeft()
        return this
    }

    fun typeCustomUrl(url: String): RelayerSettingsScreenObject {
        customField().performScrollTo().performClick()
        customField().performTextInput(url)
        return this
    }

    fun tapAddCustom(): RelayerSettingsScreenObject {
        customAddButton().performScrollTo().performClick()
        return this
    }

    private fun SemanticsNodeInteraction.performTouchInputSwipeLeft(): SemanticsNodeInteraction {
        // Compose `performTouchInput { swipeLeft() }` — extracted as
        // a helper for ergonomics across multiple tests.
        return performTouchInput { swipeLeft() }
    }
}
