package chat.onym.android.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pin the en + ru `strings.xml` files to the same set of keys so a
 * future regression that adds a string in code without translating
 * it is caught at unit-test time (the AGP `MissingTranslation` lint
 * already catches it at build time, but lint runs on the full
 * project — this test runs in the JVM-test path so misses surface
 * earlier on `:app:testDebugUnitTest`).
 *
 * Mirrors `LocalizationCatalogTests` from onym-ios PR #21.
 */
class StringsCompletenessTest {

    @Test
    fun `every English string has a Russian translation`() {
        val enKeys = readStringKeys(File(EN_PATH))
        val ruKeys = readStringKeys(File(RU_PATH))

        // EN has app_name with translatable="false" — that's the
        // only en-only entry; mirror the iOS catalog exclusion.
        val enWithoutNonTranslatable = enKeys - "app_name"

        val missingInRu = enWithoutNonTranslatable - ruKeys
        val extraInRu = ruKeys - enKeys

        assertTrue(
            "Russian strings.xml is missing translations for: ${missingInRu.sorted()}",
            missingInRu.isEmpty(),
        )
        assertTrue(
            "Russian strings.xml has keys not in English: ${extraInRu.sorted()}",
            extraInRu.isEmpty(),
        )
    }

    @Test
    fun `every plural in English has a Russian counterpart`() {
        val enPlurals = readPluralKeys(File(EN_PATH))
        val ruPlurals = readPluralKeys(File(RU_PATH))
        assertEquals(enPlurals, ruPlurals)
    }

    @Test
    fun `chain UI keys are present in both locales`() {
        // Sanity: PR #21's chain-UI catalog work landed every
        // load-bearing relayer / anchors / network / governance key.
        val mustHave = listOf(
            "relayer_title", "relayer_strategy_label", "relayer_strategy_primary",
            "relayer_strategy_random", "relayer_strategy_footer_primary",
            "relayer_strategy_footer_random", "relayer_section_configured",
            "relayer_section_add_known", "relayer_section_add_custom",
            "relayer_empty_configured", "relayer_known_all_added",
            "relayer_known_fetching", "relayer_custom_invalid",
            "anchors_title", "anchors_footer", "anchors_no_contracts_yet",
            "anchors_no_contract_for_type", "anchors_reset_to_default",
            "settings_network", "settings_network_footer",
            "network_testnet", "network_public",
            "governance_anarchy", "governance_democracy", "governance_oligarchy",
            "governance_oneonone", "governance_tyranny",
        )
        val enKeys = readStringKeys(File(EN_PATH))
        val ruKeys = readStringKeys(File(RU_PATH))
        for (key in mustHave) {
            assertTrue("$key missing from values/strings.xml", key in enKeys)
            assertTrue("$key missing from values-ru/strings.xml", key in ruKeys)
        }
    }

    private fun readStringKeys(file: File): Set<String> {
        assertNotNull("file must exist: ${file.absolutePath}", file.takeIf { it.exists() })
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("name") ?: continue
            // `translatable="false"` strings (e.g. `app_name`) are
            // intentionally en-only; exclude from the comparison.
            if (el.getAttribute("translatable") == "false") continue
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    private fun readPluralKeys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("name") ?: continue
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    private companion object {
        // Paths are relative to the Gradle module root (the `:app`
        // module), which is what the testDebugUnitTest task launches
        // from. If a future migration changes the layout, update
        // these constants to match.
        private const val EN_PATH = "src/main/res/values/strings.xml"
        private const val RU_PATH = "src/main/res/values-ru/strings.xml"
    }
}
