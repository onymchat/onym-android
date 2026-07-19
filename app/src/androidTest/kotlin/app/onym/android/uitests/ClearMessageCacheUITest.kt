package app.onym.android.uitests

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.UITestRegistry
import app.onym.android.support.InMemoryRelayerSelectionStore
import app.onym.android.uitests.screens.SettingsScreenObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the Settings → DATA → "Clear Local Message Cache" two-step
 * ("double") confirmation flow: the row opens a first dialog explaining
 * what's lost + that it can't be re-downloaded, whose confirm opens a
 * final are-you-sure, whose confirm runs the wipe and dismisses.
 *
 * The actual deletion (all messages, chats preserved) is unit-tested at
 * the repository + Room-DAO level (`MessageRepositoryTest.clearAll…`,
 * `RoomMessageStoreTest.deleteAll…`); this asserts the UI wiring + gates.
 */
@RunWith(AndroidJUnit4::class)
class ClearMessageCacheUITest {

    private val relayerStore = InMemoryRelayerSelectionStore()

    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            // Boot MainActivity through the test wiring branch. Seed the
            // relayer/contracts fakes the same way RelayerSettingsUITest
            // does so the composition root builds cleanly.
            UITestRegistry.relayerStore = relayerStore
            UITestRegistry.relayerFetcher = app.onym.android.support.FakeKnownRelayersFetcher(
                app.onym.android.support.FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())
            )
            UITestRegistry.contractsStore = app.onym.android.support.InMemoryAnchorSelectionStore()
            UITestRegistry.contractsFetcher = app.onym.android.support.FakeContractsManifestFetcher(
                app.onym.android.support.FakeContractsManifestFetcher.Mode.Succeeds(
                    app.onym.android.chain.ContractsManifest(version = 1, releases = emptyList())
                )
            )
            UITestRegistry.enabled = true
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<app.onym.android.OnymApplication>()
                .rebuildDependenciesForTest()
        }

        override fun finished(description: Description) {
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun clearMessages_requiresTwoConfirmations_thenDismisses() {
        val settings = SettingsScreenObject(composeRule)
        settings.tapClearMessagesRow()

        // First gate appears.
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("settings.clear_messages.confirm1")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Second gate is NOT shown yet — one confirmation isn't enough.
        assertTrue(
            "second gate must not appear before the first is confirmed",
            composeRule.onAllNodesWithTag("settings.clear_messages.confirm2")
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag("settings.clear_messages.confirm1").performClick()

        // Final gate appears after the first confirm.
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("settings.clear_messages.confirm2")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("settings.clear_messages.confirm2").performClick()

        // Both dialogs are gone; the wipe ran without crashing.
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("settings.clear_messages.confirm2")
                .fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("settings.clear_messages.confirm1")
                    .fetchSemanticsNodes().isEmpty()
        }
    }
}
