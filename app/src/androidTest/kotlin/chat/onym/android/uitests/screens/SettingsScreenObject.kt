package chat.onym.android.uitests.screens

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * Page object for the root Settings screen. Just the two rows we
 * navigate into from instrumented tests.
 */
class SettingsScreenObject(private val rule: ComposeContentTestRule) {
    fun tapRelayerRow() {
        rule.onNodeWithTag("settings.relayer_row").performClick()
    }

    fun tapAnchorsRow() {
        rule.onNodeWithTag("settings.anchors_row").performClick()
    }
}
