package app.onym.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [normalizeMnemonic] / [mnemonicWordCount] — the restore overlay's
 * input hygiene. Bip39 splits on a literal space only, so the overlay
 * must collapse every other whitespace shape a paste can carry
 * (newlines from password managers, tabs, doubled spaces from
 * photographed word grids) before the 12/24 gate and the validation
 * ever see the phrase. iOS's onboarding sheet counts words on any
 * whitespace but validates on spaces — the mismatch this
 * normalization exists to avoid.
 */
class MnemonicNormalizationTest {

    @Test
    fun normalize_collapsesNewlinesTabsAndDoubledSpaces() {
        assertEquals(
            "abandon ability able",
            normalizeMnemonic("abandon\nability\table"),
        )
        assertEquals(
            "abandon ability able",
            normalizeMnemonic("  abandon   ability \n\n able  "),
        )
    }

    @Test
    fun normalize_leavesACleanPhraseAlone() {
        assertEquals("abandon ability able", normalizeMnemonic("abandon ability able"))
    }

    @Test
    fun wordCount_agreesWithTheNormalizedForm() {
        assertEquals(0, mnemonicWordCount(""))
        assertEquals(0, mnemonicWordCount("   \n "))
        assertEquals(1, mnemonicWordCount(" abandon "))
        assertEquals(3, mnemonicWordCount("abandon\nability\table"))
        assertEquals(12, mnemonicWordCount(("word\n").repeat(12)))
        assertEquals(24, mnemonicWordCount(("word  ").repeat(24)))
    }
}
