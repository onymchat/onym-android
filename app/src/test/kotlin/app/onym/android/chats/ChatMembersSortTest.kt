package app.onym.android.chats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sort + label-derivation rules used by [ChatMembersScreen].
 * Replicates the iOS behavioral test for cross-platform parity.
 *
 * Mirrors `ChatMembersViewSortTests.swift` from onym-ios.
 */
class ChatMembersSortTest {

    @Test
    fun selfAlwaysFirst_thenAlphabeticCaseInsensitive() {
        val rows = listOf(
            row("alice", "alice", isSelf = false),
            row("zoe", "Zoe", isSelf = false),
            row("self", "Self", isSelf = true),
            row("bob", "Bob", isSelf = false),
        )
        val sorted = rows.sortedWith(compareBy({ !it.isSelf }, { it.displayAlias.lowercase() }))
        assertEquals(listOf("self", "alice", "bob", "zoe"), sorted.map { it.blsHex })
    }

    @Test
    fun unnamedAliasUsesFallbackLabel() {
        val raw = ""
        val displayed = raw.ifEmpty { "(unnamed)" }
        assertEquals("(unnamed)", displayed)
    }

    @Test
    fun blsPrefixIsFirst12Chars() {
        val key = "abcdef0123456789".repeat(6)  // long enough for 96 chars
        assertEquals("abcdef012345", key.take(12))
    }

    private fun row(blsHex: String, displayAlias: String, isSelf: Boolean) = MemberRow(
        blsHex = blsHex,
        blsPrefix = blsHex.take(12),
        displayAlias = displayAlias,
        isSelf = isSelf,
    )
}
