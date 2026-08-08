package app.onym.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * URL validation for the custom Blossom server input.
 *
 * Re-homed from `BlossomServersRepositoryTest` when the repository moved
 * to the :transport-blossom module — the subject
 * (`BlossomServerSettingsViewModel.validate`) lives in :app, so a
 * library test could no longer reach it.
 */
class BlossomServerSettingsViewModelTest {

    @Test
    fun viewModel_validatesScheme() {
        assertNotNull(validate("https://x.com"))
        assertNotNull(validate("http://localhost:3000"))
        assertEquals(null, validate("wss://x.com"))
        assertEquals(null, validate(""))
        assertEquals(null, validate("   "))
    }

    private fun validate(raw: String): String? =
        BlossomServerSettingsViewModel.validate(raw)
}
