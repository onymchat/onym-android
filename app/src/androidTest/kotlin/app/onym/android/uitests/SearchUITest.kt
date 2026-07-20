package app.onym.android.uitests

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.UITestRegistry
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.support.InMemoryChainLedger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.security.Security
import kotlin.time.Duration.Companion.seconds

/**
 * Focused UI coverage of the Search tab. Single-identity (the app
 * bootstraps one identity on first launch): create a group, send a few
 * messages, then search their text and open a result — asserting the
 * matched message opens in its chat thread. Uses the shared offline
 * [LoopbackRegistryHarness]; the group anchors against the in-memory
 * ledger and sending to a group of one persists locally, which is all
 * search reads.
 */
@RunWith(AndroidJUnit4::class)
class SearchUITest {

    private lateinit var identityStore: IdentitySecretStore
    private val chainLedger = InMemoryChainLedger()

    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
            val ctx = ApplicationProvider.getApplicationContext<app.onym.android.OnymApplication>()
            identityStore = IdentitySecretStore(
                ctx,
                prefsFileName = "app.onym.android.identity.searchuitests",
            )
            LoopbackRegistryHarness.configure(identityStore, chainLedger)
            ctx.rebuildDependenciesForTest()
        }

        override fun finished(description: Description) {
            try { identityStore.wipeAll() } catch (_: Throwable) { /* best-effort */ }
            UITestRegistry.reset()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun search_findsMessage_andOpensChatAtIt() {
        composeRule.waitUntil(10.seconds.inWholeMilliseconds) {
            identityStore.listIds().isNotEmpty()
        }

        createGroup("Notes")
        openTheChat("Notes")
        sendMessage("meeting at noon")
        sendMessage("lunch plans today")
        sendMessage("dinner tonight")
        waitForText("dinner tonight")

        // Search for one message, tap the result, assert its chat opens.
        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search.field").performTextInput("lunch")
        waitForTag("search.result.", prefix = true, timeout = 15.seconds)
        composeRule.onNode(
            hasTestTagStartingWith("search.result.").and(hasText("lunch plans today", substring = true)),
        ).performClick()
        waitForTag("chat_thread.input_field")
        waitForText("lunch plans today")
    }

    @Test
    fun search_noMatches_showsNoResults() {
        composeRule.waitUntil(10.seconds.inWholeMilliseconds) {
            identityStore.listIds().isNotEmpty()
        }

        createGroup("Notes")
        openTheChat("Notes")
        sendMessage("hello world")
        waitForText("hello world")

        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search.field").performTextInput("zqxjnonsense")
        // A non-matching query must surface no result rows.
        composeRule.waitForIdle()
        val hits = composeRule.onAllNodes(hasTestTagStartingWith("search.result."))
            .fetchSemanticsNodes()
        assert(hits.isEmpty()) { "a non-matching query must surface no results" }
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun createGroup(name: String) {
        goToChats()
        val empty = composeRule.onAllNodesWithTag("chats.create_group_empty_cta").fetchSemanticsNodes()
        if (empty.isNotEmpty()) {
            composeRule.onNodeWithTag("chats.create_group_empty_cta").performClick()
        } else {
            composeRule.onNodeWithTag("chats.create_group_toolbar").performClick()
        }
        waitForTag("create_group.name")
        composeRule.onNodeWithTag("create_group.name").performTextInput(name)
        composeRule.onNodeWithTag("create_group.next").performClick()
        waitForTag("create_group.create")
        composeRule.onNodeWithTag("create_group.create").performClick()
        // Real Poseidon proof + faked anchor.
        waitForTag("create_group.share_invite", timeout = 90.seconds)
        composeRule.onNodeWithTag("create_group.share_invite").performClick()
        waitForTag("share_invite.copy_button", timeout = 30.seconds)
        composeRule.onAllNodesWithText("Done").onFirst().performClick()
        composeRule.waitForIdle()
    }

    private fun openTheChat(name: String) {
        goToChats()
        waitForText(name)
        composeRule.onNode(
            hasTestTagStartingWith("chats.row.").and(hasText(name, substring = true)),
        ).performClick()
        waitForTag("chat_thread.input_field")
    }

    private fun sendMessage(body: String) {
        composeRule.onNodeWithTag("chat_thread.input_field").performTextInput(body)
        composeRule.onNodeWithTag("chat_thread.send_button").performClick()
    }

    private fun backToTabBar() {
        repeat(6) {
            if (composeRule.onAllNodesWithTag("nav.tab.search").fetchSemanticsNodes().isNotEmpty()) {
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

    private fun waitForTag(
        tag: String,
        prefix: Boolean = false,
        timeout: kotlin.time.Duration = 15.seconds,
    ) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            if (prefix) {
                composeRule.onAllNodes(hasTestTagStartingWith(tag), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            } else {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun waitForText(text: String, timeout: kotlin.time.Duration = 20.seconds) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hasTestTagStartingWith(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            tag != null && tag.startsWith(prefix)
        }
}
