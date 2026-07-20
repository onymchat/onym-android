package app.onym.android.uitests

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.MainActivity
import app.onym.android.UITestRegistry
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.support.FakeContractsManifestFetcher
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.InMemoryAnchorSelectionStore
import app.onym.android.support.InMemoryRelayerSelectionStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.security.Security
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage of Settings → Identities (PR-5). Backed by
 * an isolated [IdentitySecretStore] (per-test EncryptedSharedPreferences
 * file) injected via [UITestRegistry] so each test starts cold and
 * doesn't collide with parallel runs.
 *
 * The Application's eager bootstrap auto-creates the first identity,
 * so every test starts with one identity already on disk + selected.
 *
 * What's exercised:
 *
 *  - Settings → "Manage identities" navigation lands on the screen
 *    and shows the bootstrapped identity.
 *  - "+ Add identity" FAB appends a second row.
 *  - Trash → name-confirm dialog → confirm removes the row + cascades
 *    via the `IdentityRepository.registerRemovalListener` hook
 *    (PR-3 wiring; the listener is registered in `init` on
 *    `GroupRepository`).
 */
@RunWith(AndroidJUnit4::class)
class IdentitiesUITest {

    private lateinit var identityStore: IdentitySecretStore

    @get:Rule(order = 0)
    val registrySetup = object : TestWatcher() {
        override fun starting(description: Description) {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
            val ctx = ApplicationProvider.getApplicationContext<app.onym.android.OnymApplication>()
            identityStore = IdentitySecretStore(
                ctx,
                prefsFileName = "app.onym.android.identity.identitiesuitests",
            )
            // Seed every other store IdentitiesUITest doesn't directly
            // assert on, so MainActivity's Application.onCreate doesn't
            // trip on a missing relayer/contracts wiring.
            UITestRegistry.identitySecretStore = identityStore
            UITestRegistry.relayerStore = InMemoryRelayerSelectionStore().apply {
                kotlinx.coroutines.runBlocking {
                    saveConfiguration(
                        app.onym.android.chain.RelayerConfiguration(
                            endpoints = listOf(
                                app.onym.android.chain.RelayerEndpoint(
                                    "test", "https://relayer.test.invalid", listOf("testnet"),
                                ),
                            ),
                            hasUserInteracted = true,
                        )
                    )
                }
            }
            UITestRegistry.relayerFetcher = FakeKnownRelayersFetcher(
                FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())
            )
            UITestRegistry.contractsStore = InMemoryAnchorSelectionStore()
            UITestRegistry.contractsFetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(
                    app.onym.android.chain.ContractsManifest(
                        version = 1,
                        releases = emptyList(),
                    ),
                )
            )
            UITestRegistry.enabled = true
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
    fun bootstrap_carousel_showsOneActiveIdentity() {
        openCarousel()
        // Wait for the bootstrap-from-zero path to populate the identities
        // flow (eager bootstrap kicks off when the Application's deps are
        // built; the carousel's StateFlow collector picks it up shortly
        // after) and land on a non-empty active alias.
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1 && carouselActiveName()?.isNotEmpty() == true
        }
    }

    @Test
    fun addIdentity_viaCarousel_appendsAndActivates() {
        openCarousel()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
        // Swipe to the trailing add page, type a name, create → a second
        // identity lands on disk and becomes the active (visible) page.
        addViaCarousel("Work")
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 2
        }
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            carouselActiveName() == "Work"
        }
    }

    @Test
    fun removeIdentity_viaCarousel_typedConfirm_deletesFromStore() {
        openCarousel()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
        // Add a second identity so we can remove it (removing the only one
        // leaves "no identity", which the bootstrap path would re-fill on
        // the next dependency rebuild, complicating the assertion).
        val before = identityStore.listIds().toSet()
        addViaCarousel("Work")
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 2
        }
        // The carousel lands on the newly-added "Work" page. Its Delete
        // action opens the typed-name confirm dialog (shared with the old
        // detail screen — same tags).
        val workId = identityStore.listIds().first { it !in before }
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            carouselActiveName() == "Work"
        }
        composeRule.onNodeWithTag("identity.delete.${workId.value}").performClick()
        composeRule.waitUntil(timeoutMillis = 15.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("identity_detail.delete.confirm.input")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("identity_detail.delete.confirm.input")
            .performTextInput("Work")
        composeRule.onNodeWithTag("identity_detail.delete.confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
    }

    @Test
    fun renameIdentity_viaCarousel_updatesActiveAlias() {
        openCarousel()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
        // Add "Work" (lands active), then rename it to "Job" via the
        // tap-the-name dialog. The carousel's active-alias hook should
        // reflect the new name.
        val before = identityStore.listIds().toSet()
        addViaCarousel("Work")
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 2 && carouselActiveName() == "Work"
        }
        val workId = identityStore.listIds().first { it !in before }
        composeRule.onNodeWithTag("identity.rename.${workId.value}").performClick()
        composeRule.waitUntil(timeoutMillis = 15.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("identity.rename.input")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("identity.rename.input").performTextReplacement("Job")
        composeRule.onNodeWithTag("identity.rename.confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            carouselActiveName() == "Job"
        }
    }

    @Test
    fun restoreIdentity_viaCarousel_addsIdentity() {
        openCarousel()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
        // Add page → "Restore from recovery phrase" → enter the canonical
        // BIP-39 vector → Restore. It's a valid phrase, so a (deterministic)
        // second identity lands on disk.
        swipeToAddPage()
        composeRule.onNodeWithTag("identity.add.restore").performClick()
        composeRule.waitUntil(timeoutMillis = 15.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("identity.restore.phrase")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("identity.restore.phrase").performTextInput(CANONICAL_MNEMONIC)
        composeRule.onNodeWithTag("identity.restore.confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 2
        }
    }

    // ─── carousel helpers ──────────────────────────────────────────────

    /** Open Settings and scroll the merged Identity carousel into view. */
    private fun openCarousel() {
        composeRule.onNodeWithTag("nav.tab.settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("identity.carousel").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Swipe to the trailing add page, enter [name], tap Create. Waits for
     *  the add page to actually *settle* (be centered) before tapping —
     *  neighbor pages are composed off-screen, where a tap never lands. The
     *  add page is the last one: index == identity count. */
    private fun addViaCarousel(name: String) {
        val addIndex = identityStore.listIds().size
        var tries = 0
        while (carouselSettledPage() != addIndex && tries < 8) {
            composeRule.onNodeWithTag("identity.carousel").performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            tries++
        }
        composeRule.waitUntil(timeoutMillis = 15.seconds.inWholeMilliseconds) {
            carouselSettledPage() == addIndex
        }
        composeRule.onNodeWithTag("identity.add.name").performTextInput(name)
        composeRule.onNodeWithTag("identity.add.create").performClick()
    }

    /** Swipe to the trailing add page and wait for it to settle (centered). */
    private fun swipeToAddPage() {
        val addIndex = identityStore.listIds().size
        var tries = 0
        while (carouselSettledPage() != addIndex && tries < 8) {
            composeRule.onNodeWithTag("identity.carousel").performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            tries++
        }
        composeRule.waitUntil(timeoutMillis = 15.seconds.inWholeMilliseconds) {
            carouselSettledPage() == addIndex
        }
    }

    private companion object {
        /** Canonical BIP-39 test vector (`abandon × 11 + about`). */
        const val CANONICAL_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon about"
    }

    /** Alias of the currently-active identity, read from the carousel's
     *  hidden `identity.carousel.active` ContentDescription hook. */
    private fun carouselActiveName(): String? =
        composeRule.onAllNodesWithTag("identity.carousel.active")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.ContentDescription)
            ?.firstOrNull()

    /** The carousel's currently-settled page index, from its hidden hook. */
    private fun carouselSettledPage(): Int? =
        composeRule.onAllNodesWithTag("identity.carousel.settled")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.ContentDescription)
            ?.firstOrNull()
            ?.toIntOrNull()
}
