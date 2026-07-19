package app.onym.android.uitests

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.UITestRegistry
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.support.InMemoryChainLedger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.locale.LocaleTestRule
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import java.security.Security
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Google Play screenshot generator, driven by `fastlane screenshots`
 * (`capture_android_screenshots`, see fastlane/Screengrabfile). The
 * Android twin of onym-ios's `ScreenshotUITests`.
 *
 * Runs on the offline [UITestRegistry] harness (in-process transports +
 * in-memory chain ledger, real Poseidon proof), so it's deterministic
 * and needs no network. It seeds a group + a short conversation and
 * captures the key screens; [LocaleTestRule] + screengrab re-run the
 * whole flow once per configured locale (en-US, ru-RU).
 *
 * All seeded content (group name, invitation, messages) is user text —
 * identical across locales — so the waits below are language-independent.
 * Navigation avoids localized button text: it pops the create flow with
 * back, not by tapping "Done"/"Готово".
 *
 * FLAG_SECURE (set unconditionally in production) is suppressed while
 * [UITestRegistry.enabled] is true — see MainActivity.onCreate — so the
 * captured frames aren't black.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    private lateinit var identityStore: IdentitySecretStore
    private val chainLedger = InMemoryChainLedger()

    // Locale is switched by screengrab before each pass; order 0 so it's
    // in place before the harness rule and the Activity launch.
    @get:Rule(order = 0)
    val localeRule = LocaleTestRule()

    // Same offline-harness setup as MultiIdentityChatUITest: insert
    // BouncyCastle, build a fresh per-run identity store, wire the
    // loopback registry, and rebuild dependencies before the Activity
    // starts (which also flips FLAG_SECURE off for capture).
    @get:Rule(order = 1)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
            val ctx = ApplicationProvider.getApplicationContext<app.onym.android.OnymApplication>()
            identityStore = IdentitySecretStore(
                ctx,
                prefsFileName = "app.onym.android.identity.screenshots.${UUID.randomUUID()}",
            )
            LoopbackRegistryHarness.configure(identityStore, chainLedger)
            ctx.rebuildDependenciesForTest()
        }

        override fun finished(description: Description) {
            try { identityStore.wipeAll() } catch (_: Throwable) { /* best-effort */ }
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUpScreengrab() {
        Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())
    }

    @Test
    fun generateScreenshots() {
        // Wait for the eager bootstrap identity so the app is stable.
        composeRule.waitUntil(10.seconds.inWholeMilliseconds) {
            identityStore.listIds().isNotEmpty()
        }

        // 1 — Create Group: name + invitation message.
        openCreateGroup()
        composeRule.onNodeWithTag("create_group.name")
            .performTextInput("Weekend Trip")
        composeRule.onNodeWithTag("create_group.invitation")
            .performTextInput(INVITATION)
        composeRule.waitForIdle()
        Screengrab.screenshot("02_create_group")

        // Finish creating (real Poseidon proof + faked anchor), then pop
        // back to the Chats list with back (locale-independent).
        composeRule.onNodeWithTag("create_group.next").performClick()
        waitForTag("create_group.create")
        composeRule.onNodeWithTag("create_group.create").performClick()
        waitForTag("create_group.share_invite", timeout = 90.seconds)
        goToChats()

        // 2 — Chats list with the new group.
        waitForText("Weekend Trip")
        Screengrab.screenshot("03_chats")

        // 3 — Chat welcome: the rich empty state (invitation + privacy).
        clickRowContaining("chats.row.", "Weekend Trip")
        waitForTag("chat_thread.input_field")
        waitForTag("chat_thread.empty")
        waitForText("House rules") // the invitation is user text (any locale)
        Screengrab.screenshot("04_welcome")

        // 4 — A conversation.
        sendMessage("Landing at 4 — who's grabbing the keys?")
        waitForText("Landing at 4")
        sendMessage("Got them 🔑 see you at the cabin")
        waitForText("see you at the cabin")
        Screengrab.screenshot("05_chat")

        // 5 — Identity & invite (Settings carousel). Captured last: by now
        // the bootstrapped identity is fully loaded.
        openSettingsCarousel()
        composeRule.waitForIdle()
        Screengrab.screenshot("01_identity")
    }

    // ─── helpers (subset of MultiIdentityChatUITest) ──────────────

    private fun openCreateGroup() {
        goToChats()
        val empty = composeRule.onAllNodesWithTag("chats.create_group_empty_cta")
            .fetchSemanticsNodes()
        if (empty.isNotEmpty()) {
            composeRule.onNodeWithTag("chats.create_group_empty_cta").performClick()
        } else {
            composeRule.onNodeWithTag("chats.create_group_toolbar").performClick()
        }
        waitForTag("create_group.name")
    }

    private fun sendMessage(body: String) {
        composeRule.onNodeWithTag("chat_thread.input_field").performTextInput(body)
        composeRule.onNodeWithTag("chat_thread.send_button").performClick()
    }

    private fun openSettingsCarousel() {
        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings.list")
            .performScrollToNode(hasTestTag("identity.carousel"))
    }

    private fun backToTabBar() {
        repeat(6) {
            if (composeRule.onAllNodesWithTag("nav.tab.settings").fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            Espresso.pressBack()
            composeRule.waitForIdle()
        }
    }

    private fun goToChats() {
        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.chats").performClick()
        composeRule.waitForIdle()
    }

    private fun clickRowContaining(tagPrefix: String, text: String) {
        composeRule.onNode(
            hasTestTagStartingWith(tagPrefix).and(hasText(text, substring = true)),
        ).performClick()
    }

    private fun waitForTag(tag: String, timeout: Duration = 15.seconds) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String, timeout: Duration = 20.seconds) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hasTestTagStartingWith(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            tag != null && tag.startsWith(prefix)
        }

    private companion object {
        const val INVITATION =
            "House rules: be kind, share the good photos, no spoilers. " +
                "Glad you're here — let's plan the weekend."
    }
}
