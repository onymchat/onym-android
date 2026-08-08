package app.onym.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * URL validation for the custom Nostr relay input.
 *
 * Re-homed from `NostrRelaysRepositoryTest` when the repository moved
 * to the :transport-nostr module — the subject
 * (`NostrRelaySettingsViewModel.validate`) lives in :app, so a library
 * test could no longer reach it.
 */
class NostrRelaySettingsViewModelTest {

    @Test
    fun viewModel_validatesScheme() {
        assertNotNull(validate("wss://x.com"))
        assertNotNull(validate("ws://localhost:7777"))
        assertEquals(null, validate("https://x.com"))
        assertEquals(null, validate(""))
        assertEquals(null, validate("   "))
    }

    private fun validate(raw: String): String? =
        NostrRelaySettingsViewModel.validate(raw)
}
