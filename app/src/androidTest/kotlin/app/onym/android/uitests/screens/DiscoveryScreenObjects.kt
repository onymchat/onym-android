package app.onym.android.uitests.screens

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlin.time.Duration.Companion.seconds

/**
 * Page object for Settings → Discovery
 * ([app.onym.android.settings.DiscoverySettingsScreen]). Wraps the
 * screen's test-tag vocabulary (`discovery.source.<providerId>`,
 * `discovery.add_row`, `discovery.remove.confirm`, …) in the same
 * style as [RelayerSettingsScreenObject].
 */
class DiscoverySettingsScreenObject(private val rule: ComposeContentTestRule) {

    fun sourceRow(providerId: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("discovery.source.$providerId")

    fun removeButton(providerId: String): SemanticsNodeInteraction =
        rule.onNodeWithTag("discovery.source.$providerId.remove")

    fun emptyState(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.empty")
    fun addRow(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add_row")

    fun assertSourceListed(providerId: String): DiscoverySettingsScreenObject {
        sourceRow(providerId).performScrollTo().assertIsDisplayed()
        return this
    }

    fun assertEmpty(): DiscoverySettingsScreenObject {
        emptyState().performScrollTo().assertIsDisplayed()
        return this
    }

    fun tapAddProvider(): DiscoverySettingsScreenObject {
        addRow().performScrollTo().performClick()
        return this
    }

    fun tapRemove(providerId: String): DiscoverySettingsScreenObject {
        removeButton(providerId).performScrollTo().performClick()
        return this
    }

    /** The destructive confirm inside the removal AlertDialog. */
    fun confirmRemove(): DiscoverySettingsScreenObject {
        rule.onNodeWithTag("discovery.remove.confirm").performClick()
        return this
    }
}

/**
 * Page object for the add-provider flow
 * ([app.onym.android.settings.AddDiscoveryProviderScreen]): URL entry
 * → fetch → TOFU fingerprint confirmation → done.
 */
class AddDiscoveryProviderScreenObject(private val rule: ComposeContentTestRule) {

    fun urlField(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add.field")
    fun fetchButton(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add.fetch")
    fun errorLabel(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add.error")
    fun fingerprint(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add.fingerprint")
    fun confirmButton(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add.confirm")
    fun doneButton(): SemanticsNodeInteraction = rule.onNodeWithTag("discovery.add.done_button")

    fun typeManifestUrl(url: String): AddDiscoveryProviderScreenObject {
        urlField().performScrollTo().performClick()
        urlField().performTextInput(url)
        return this
    }

    fun tapFetch(): AddDiscoveryProviderScreenObject {
        fetchButton().performScrollTo().performClick()
        return this
    }

    /** Wait for the fetched manifest to reach the TOFU confirmation
     *  step (the fingerprint card is its anchor node). */
    fun awaitFingerprint(): AddDiscoveryProviderScreenObject {
        rule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("discovery.add.fingerprint")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    /** The exact grouped-hex fingerprint the user is asked to check
     *  before anything is pinned. */
    fun assertFingerprint(expected: String): AddDiscoveryProviderScreenObject {
        fingerprint().performScrollTo().assertTextEquals(expected)
        return this
    }

    fun tapConfirm(): AddDiscoveryProviderScreenObject {
        confirmButton().performScrollTo().performClick()
        return this
    }

    fun awaitDone(): AddDiscoveryProviderScreenObject {
        rule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            rule.onAllNodesWithTag("discovery.add.done_button")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }

    fun tapDone(): AddDiscoveryProviderScreenObject {
        doneButton().performScrollTo().performClick()
        return this
    }
}
