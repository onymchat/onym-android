package chat.onym.android.uitests

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.MainActivity
import chat.onym.android.UITestRegistry
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.support.FakeContractsManifestFetcher
import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryAnchorSelectionStore
import chat.onym.android.support.InMemoryRelayerSelectionStore
import chat.onym.android.uitests.screens.SettingsScreenObject
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
            val ctx = ApplicationProvider.getApplicationContext<chat.onym.android.OnymApplication>()
            identityStore = IdentitySecretStore(
                ctx,
                prefsFileName = "chat.onym.android.identity.uitests.${UUID.randomUUID()}",
            )
            // Seed every other store IdentitiesUITest doesn't directly
            // assert on, so MainActivity's Application.onCreate doesn't
            // trip on a missing relayer/contracts wiring.
            UITestRegistry.identitySecretStore = identityStore
            UITestRegistry.relayerStore = InMemoryRelayerSelectionStore().apply {
                kotlinx.coroutines.runBlocking {
                    saveConfiguration(
                        chat.onym.android.chain.RelayerConfiguration(
                            endpoints = listOf(
                                chat.onym.android.chain.RelayerEndpoint(
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
                    chat.onym.android.chain.ContractsManifest(
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

    private val settings get() = SettingsScreenObject(composeRule)

    @Test
    fun bootstrap_landsOnIdentitiesScreen_withOneRow() {
        settings.tapIdentitiesRow()

        // Wait for the bootstrap-from-zero path to populate the
        // identities flow (eager bootstrap kicks off when the
        // Application's deps are built; the screen's StateFlow
        // collector picks it up shortly after).
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("identities.add").fetchSemanticsNodes().isNotEmpty() &&
                identityStore.listIds().isNotEmpty()
        }
        // Exactly one identity row is visible.
        val rowCount = composeRule.onAllNodesWithTag(
            identityStore.listIds().first().testTagPrefix(),
        ).fetchSemanticsNodes().size
        assert(rowCount == 1) { "expected 1 identity row, saw $rowCount" }
    }

    @Test
    fun addIdentity_appendsRow() {
        settings.tapIdentitiesRow()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
        composeRule.onNodeWithTag("identities.add").performClick()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 2
        }
    }

    @Test
    fun removeIdentity_typedConfirm_deletesFromStore() {
        settings.tapIdentitiesRow()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
        // Add a second identity so we can remove the second one
        // (removing the only one is allowed but the post-remove state
        // is "no identity" — the bootstrap path would re-add one on
        // the next dependency rebuild, complicating the assertion).
        composeRule.onNodeWithTag("identities.add").performClick()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 2
        }

        // The newly-added identity gets the auto-fill name "Identity 2".
        // Post-redesign: removal moved into IdentityDetailScreen — tap
        // the row to drill in, then "Delete identity" → typed-name
        // confirm → Delete.
        val secondId = identityStore.listIds()[1]
        composeRule.onNodeWithTag("identities.row.${secondId.value}").performClick()
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag("identity_detail.delete").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("identity_detail.delete").performClick()

        // Type the expected name; tap Delete.
        composeRule.onNodeWithTag("identity_detail.delete.confirm.input")
            .performTextInput("Identity 2")
        composeRule.onNodeWithTag("identity_detail.delete.confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            identityStore.listIds().size == 1
        }
    }

    /** `identities.row.<id>` is the row's tag prefix; `.remove`
     *  appends the trash-icon's selector. Pulled into a helper so
     *  the row-tag scheme stays in one place. */
    private fun chat.onym.android.identity.IdentityId.testTagPrefix(): String =
        "identities.row.$value"
}
