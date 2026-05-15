package app.onym.android.uitests.screens

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

/**
 * Page object for the root Settings screen. Page-object navigation
 * helpers for the rows the rest of the suite cares about.
 *
 * Two reasons every method opens the Settings tab first AND scrolls
 * the target row into view before tapping it:
 *
 *  1. **Tab discipline**: post-PR-30 the start destination is the
 *     Chats tab, not Settings. Without `openSettingsTab` the rows
 *     still resolve in the NavHost (Chats is mounted concurrently)
 *     but their compose bounds aren't on the visible window, so
 *     `performClick` fails with `Failed to inject touch input`
 *     (run #25263258259 surfaced this on every AnchorsUITest /
 *     RelayerSettingsUITest case).
 *  2. **Scroll discipline**: post-multi-identity (PR-5) Settings
 *     gained an "Identities" row between Security and Network.
 *     On smaller test windows the Network rows (Relayer / Anchors /
 *     Use Mainnet toggle) can sit below the fold. `scrollAndClick`
 *     uses `performScrollToNode` against the LazyColumn's
 *     `settings.list` tag so the target row is guaranteed visible
 *     before the touch.
 */
class SettingsScreenObject(private val rule: ComposeContentTestRule) {

    fun tapRelayerRow() = scrollAndClick("settings.relayer_row")
    fun tapAnchorsRow() = scrollAndClick("settings.anchors_row")
    fun tapIdentitiesRow() = scrollAndClick("settings.identities_row")

    private fun scrollAndClick(tag: String) {
        openSettingsTab()
        rule.onNodeWithTag("settings.list")
            .performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
    }

    /** Tap the Settings tab in the bottom NavigationBar. Idempotent
     *  — if the user is already on the Settings tab the tap is a
     *  no-op (single-top + restoreState in [app.onym.android.RootScreen]). */
    private fun openSettingsTab() {
        rule.onNodeWithTag("nav.tab.settings").performClick()
        rule.waitForIdle()
    }
}
