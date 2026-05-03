package chat.onym.android.uitests.screens

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * Page object for the root Settings screen. Just the two rows we
 * navigate into from instrumented tests.
 *
 * Post-PR-30 the start destination is the Chats tab, not Settings —
 * so every method here first taps the Settings bottom-bar tab to
 * make sure the rows we want are in the visible window. Without
 * that hop the rows still exist in the NavHost (Chats is also
 * mounted) but their compose bounds aren't on-screen, so
 * `performClick` fails with `Failed to inject touch input`
 * (run #25263258259 surfaced this on every AnchorsUITest /
 * RelayerSettingsUITest case).
 */
class SettingsScreenObject(private val rule: ComposeContentTestRule) {

    fun tapRelayerRow() {
        openSettingsTab()
        rule.onNodeWithTag("settings.relayer_row").performClick()
    }

    fun tapAnchorsRow() {
        openSettingsTab()
        rule.onNodeWithTag("settings.anchors_row").performClick()
    }

    /** Tap the Settings tab in the bottom NavigationBar. Idempotent
     *  — if the user is already on the Settings tab the tap is a
     *  no-op (single-top + restoreState in [chat.onym.android.RootScreen]). */
    private fun openSettingsTab() {
        rule.onNodeWithTag("nav.tab.settings").performClick()
        rule.waitForIdle()
    }
}
