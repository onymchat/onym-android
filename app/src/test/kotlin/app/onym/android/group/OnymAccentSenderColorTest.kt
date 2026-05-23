package app.onym.android.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the per-sender accent derivation that drives chat
 * sender-differentiation. The contract that matters:
 *
 *  - **Deterministic**: the same BLS pubkey always resolves to the
 *    same accent (so a person's color is stable across devices,
 *    groups, and launches — it's a visual fingerprint).
 *  - **Keyed on the pubkey alone**: the function never sees the alias,
 *    so an alias-spoofer can't steal the original's color.
 *  - **Spread across the palette**: not a constant.
 *  - **Cross-platform parity**: byte-identical to
 *    `OnymAccent.forSender(blsPubkeyHex:)` in onym-ios PR #162 — same
 *    FNV-1a constants over the same UTF-8 bytes into the same palette
 *    order. The reference-vector test below pins the algorithm so a
 *    constant typo can't silently desync the two platforms' colors.
 *
 * Mirrors `OnymAccentSenderColorTests.swift` from onym-ios PR #162.
 */
class OnymAccentSenderColorTest {

    @Test
    fun forSender_isDeterministic() {
        val hex = "ab".repeat(48) // 96-char BLS pubkey hex
        assertEquals(
            "same pubkey must always map to the same accent",
            OnymAccent.forSender(hex),
            OnymAccent.forSender(hex),
        )
    }

    @Test
    fun forSender_distinctPubkeysCanDiffer() {
        // Generate a spread of pubkeys and confirm the mapping isn't a
        // constant — at least two distinct accents appear. (A perfect
        // even split isn't promised; non-degeneracy is.)
        val accents = (0 until 200)
            .map { i -> OnymAccent.forSender("%096x".format(i)) }
            .toSet()
        assertTrue(
            "the hash must distribute senders across the palette",
            accents.size > 1,
        )
    }

    @Test
    fun forSender_alwaysReturnsPaletteMember() {
        val accent = OnymAccent.forSender("ff".repeat(48))
        assertTrue(OnymAccent.entries.contains(accent))
    }

    @Test
    fun forSender_matchesReferenceFnv1a() {
        // Independent re-implementation of the documented algorithm —
        // guards production `forSender` against a constant/ordering
        // drift that would desync colors from iOS.
        fun reference(hex: String): OnymAccent {
            var hash = 0xcbf2_9ce4_8422_2325uL
            for (b in hex.encodeToByteArray()) {
                hash = hash xor b.toUByte().toULong()
                hash *= 0x0000_0100_0000_01b3uL
            }
            return OnymAccent.entries[(hash % OnymAccent.entries.size.toULong()).toInt()]
        }
        for (hex in listOf("", "a", "ab".repeat(48), "ff".repeat(48), "deadbeef")) {
            assertEquals(
                "forSender must equal the reference FNV-1a mapping for '$hex'",
                reference(hex),
                OnymAccent.forSender(hex),
            )
        }
    }
}
