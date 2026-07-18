package app.onym.android.uitests

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end UI coverage of two identities on ONE device exchanging
 * chat messages through a real Founder (Tyranny) group, including read
 * receipts in both directions. Android twin of onym-ios's
 * `MultiIdentityChatUITests`.
 *
 * ## Offline harness ([UITestRegistry])
 *
 * The production build talks to real Nostr relays + the SEP contract
 * relayer. Those can't run in CI, so the registry injects:
 *   - [LoopbackInboxTransport] — in-process, store-and-forward inbox
 *     routing so the two identities' inboxes exchange invitations /
 *     messages / receipts with no network.
 *   - [InMemoryChainLedger] + [LedgerSepContractTransport] — an
 *     in-memory stand-in for on-chain state, fed by both the
 *     `create_group` / `update_commitment` writes and the
 *     `get_commitment` reads, so the Tyranny group anchors and then
 *     verifies against the exact same commitment. The Poseidon proof
 *     itself stays real FFI — only the relayer round-trip is faked.
 *
 * The join deeplink is delivered as a real `ACTION_VIEW` intent with
 * `FLAG_ACTIVITY_SINGLE_TOP` (warm-start `onNewIntent`), so it exercises
 * the app's real `DeeplinkCapture` path. Everything runs in one process
 * — the loopback transport + ledger are singletons that survive the
 * intent.
 */
@RunWith(AndroidJUnit4::class)
class MultiIdentityChatUITest {

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
                prefsFileName = "app.onym.android.identity.uitests.${UUID.randomUUID()}",
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
    fun founderGroup_twoIdentities_messageRoundTrip_withReadReceipts() {
        // Wait for the eager bootstrap identity so the list is stable.
        composeRule.waitUntil(10.seconds.inWholeMilliseconds) {
            identityStore.listIds().isNotEmpty()
        }

        // 1. Create two identities: Alice, Bob.
        addIdentity("Alice")
        addIdentity("Bob")

        // 2. Alice creates a Founder group.
        switchToIdentity("Alice")
        openCreateGroup()
        composeRule.onNodeWithTag("create_group.name").performTextInput("Founders")
        composeRule.onNodeWithTag("create_group.next").performClick()
        waitForTag("create_group.create")
        composeRule.onNodeWithTag("create_group.create").performClick()
        // Real Poseidon proof + faked anchor.
        waitForTag("create_group.share_invite", timeout = 90.seconds)

        // 3. Share the invite and read the link off the copy button.
        composeRule.onNodeWithTag("create_group.share_invite").performClick()
        waitForTag("share_invite.copy_button", timeout = 30.seconds)
        val inviteLink = readContentDescription("share_invite.copy_button")
        require(!inviteLink.isNullOrEmpty()) { "invite link was not exposed by the share screen" }
        // Leave the create flow (Done in the share top bar).
        composeRule.onAllNodesWithText("Done").onFirst().performClick()
        composeRule.waitForIdle()

        // 4. Bob accepts the invitation via the deeplink.
        switchToIdentity("Bob")
        deliverDeeplink(inviteLink)
        waitForTag("join.label_field")
        composeRule.onNodeWithTag("join.label_field").performTextInput("Bob")
        composeRule.onNodeWithTag("join.send_button").performClick()
        waitForTag("join.awaiting_approval")

        // 5. Alice approves the join request.
        switchToIdentity("Alice")
        composeRule.onNodeWithTag("approve_requests.toolbar_button").performClick()
        waitForTag("approve_requests.approve_button", prefix = true, timeout = 45.seconds)
        composeRule.onAllNodes(
            hasTestTagStartingWith("approve_requests.approve_button"),
            useUnmergedTree = true,
        ).onFirst().performClick()
        waitForTag("approve_requests.success_banner", timeout = 60.seconds)
        Espresso.pressBack() // close the approve sheet
        composeRule.waitForIdle()

        // 6. Bob -> Alice message + read receipt.
        switchToIdentity("Bob")
        openTheChat()
        sendMessage("Hello from Bob")
        waitForText("Hello from Bob")

        switchToIdentity("Alice")
        openTheChat()
        waitForText("Hello from Bob") // received; viewing sends a read receipt
        switchToIdentity("Bob")
        openTheChat()
        waitForContentDescription("Read", timeout = 40.seconds) // Bob sees his message was read

        // 7. Alice -> Bob message + read receipt (other direction).
        switchToIdentity("Alice")
        openTheChat()
        sendMessage("Hello from Alice")
        waitForText("Hello from Alice")

        switchToIdentity("Bob")
        openTheChat()
        waitForText("Hello from Alice")
        switchToIdentity("Alice")
        openTheChat()
        waitForContentDescription("Read", timeout = 40.seconds)

        // 8. Alice -> Bob image message. The attach button, under the
        //    UI-test harness, sends a generated test image straight
        //    through the encode → encrypt → upload(loopback) pipeline.
        switchToIdentity("Alice")
        openTheChat()
        sendImage()
        // The sender renders its own image bubble (primed cache).
        waitForImageBubble()

        // 9. Bob receives it: the bubble lazily downloads from the
        //    loopback Blossom store, hash-verifies, decrypts, and
        //    renders — proving the full cross-identity image round-trip.
        switchToIdentity("Bob")
        openTheChat()
        waitForImageBubble(timeout = 40.seconds)

        // 10. Bob -> Alice video message. The attach-video button, under
        //     the UI-test harness, sends a canned video (both poster and
        //     video blobs) through the encode → encrypt → upload pipeline.
        switchToIdentity("Bob")
        openTheChat()
        sendVideo()
        waitForVideoBubble()

        // 11. Alice receives it: her bubble lazily downloads the poster
        //     from the loopback Blossom store, decrypts, and renders —
        //     proving the cross-identity video round-trip.
        switchToIdentity("Alice")
        openTheChat()
        waitForVideoBubble(timeout = 40.seconds)

        // 12. Search (as Alice): find Bob's message text, tap the result,
        //     and assert it opens the chat thread scrolled to that message.
        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search.field").performTextInput("Hello from Bob")
        waitForTag("search.result.", prefix = true, timeout = 15.seconds)
        composeRule.onNode(
            hasTestTagStartingWith("search.result.").and(hasText("Hello from Bob", substring = true)),
        ).performClick()
        // Tapping the result opens the thread (composer present) with the
        // matched message rendered — proving search → open-at-message.
        waitForTag("chat_thread.input_field")
        waitForText("Hello from Bob")
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun addIdentity(name: String) {
        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings.list")
            .performScrollToNode(hasTestTag("settings.identities_row"))
        composeRule.onNodeWithTag("settings.identities_row").performClick()
        waitForTag("identities.add")
        composeRule.onNodeWithTag("identities.add").performClick()
        waitForTag("identities.add.name")
        composeRule.onNodeWithTag("identities.add.name").performTextInput(name)
        composeRule.onNodeWithTag("identities.add.confirm").performClick()
        waitForText(name)
        backToTabBar()
    }

    /** Settings -> Identities -> tap the named row -> Set active -> back to Chats. */
    private fun switchToIdentity(name: String) {
        backToTabBar()
        composeRule.onNodeWithTag("nav.tab.settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings.list")
            .performScrollToNode(hasTestTag("settings.identities_row"))
        composeRule.onNodeWithTag("settings.identities_row").performClick()
        waitForText(name)
        clickRowContaining("identities.row.", name)
        // The detail screen is a LazyColumn; Set active sits below the
        // fold, so scroll it into view before tapping.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("identity_detail.list")
            .performScrollToNode(hasTestTag("identity_detail.set_active"))
        composeRule.onNodeWithTag("identity_detail.set_active").performClick()
        composeRule.waitForIdle()
        goToChats()
    }

    private fun openCreateGroup() {
        goToChats()
        val empty = composeRule.onAllNodesWithTag("chats.create_group_empty_cta").fetchSemanticsNodes()
        if (empty.isNotEmpty()) {
            composeRule.onNodeWithTag("chats.create_group_empty_cta").performClick()
        } else {
            composeRule.onNodeWithTag("chats.create_group_toolbar").performClick()
        }
        waitForTag("create_group.name")
    }

    /** Open the single group's thread from the Chats list. */
    private fun openTheChat() {
        goToChats()
        waitForText("Founders")
        clickRowContaining("chats.row.", "Founders")
        waitForTag("chat_thread.input_field")
    }

    private fun sendMessage(body: String) {
        composeRule.onNodeWithTag("chat_thread.input_field").performTextInput(body)
        composeRule.onNodeWithTag("chat_thread.send_button").performClick()
    }

    /** Tap the composer's attach button. Under [UITestRegistry.enabled]
     *  the screen bypasses the system photo picker and sends a generated
     *  test image through the real send pipeline. */
    private fun sendImage() {
        composeRule.onNodeWithTag("chat_thread.attach_button").performClick()
    }

    /** Wait until at least one image-attachment bubble is rendered. */
    private fun waitForImageBubble(timeout: kotlin.time.Duration = 20.seconds) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            composeRule.onAllNodes(hasTestTagStartingWith("chat_thread.image."))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Tap the composer's attach-video button. Under [UITestRegistry.enabled]
     *  the screen bypasses the picker + Media3 transcoding and sends a
     *  canned video through the real send pipeline. */
    private fun sendVideo() {
        composeRule.onNodeWithTag("chat_thread.attach_video_button").performClick()
    }

    /** Wait until at least one video-attachment bubble (poster) is rendered. */
    private fun waitForVideoBubble(timeout: kotlin.time.Duration = 20.seconds) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            composeRule.onAllNodes(hasTestTagStartingWith("chat_thread.video."))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun deliverDeeplink(link: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(link),
        ).apply {
            setPackage(ctx.packageName)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        ctx.startActivity(intent)
        composeRule.waitForIdle()
    }

    // ─── navigation + waiting utilities ───────────────────────────

    /** Press back until the bottom tab bar is showing (a tab route). */
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

    private fun waitForContentDescription(desc: String, timeout: kotlin.time.Duration = 20.seconds) {
        composeRule.waitUntil(timeout.inWholeMilliseconds) {
            composeRule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun readContentDescription(tag: String): String? {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }

    /** Click the clickable row whose testTag starts with [tagPrefix]
     *  and whose (merged) text contains [text]. Targets the row itself
     *  rather than a bare inner Text node, so the click reaches the
     *  row's onClick. */
    private fun clickRowContaining(tagPrefix: String, text: String) {
        composeRule.onNode(
            hasTestTagStartingWith(tagPrefix).and(hasText(text, substring = true)),
        ).performClick()
    }

    private fun hasTestTagStartingWith(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            tag != null && tag.startsWith(prefix)
        }
}
