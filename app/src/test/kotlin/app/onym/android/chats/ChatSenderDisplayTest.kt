package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.group.MemberProfile
import app.onym.android.group.OnymAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pure-policy tests for chat sender-differentiation run-grouping +
 * alias resolution. Exercises [buildSenderDisplays] / [senderName]
 * without standing up Compose — the production screen wires the same
 * functions through a `remember(messages, memberProfiles)` and feeds
 * each bubble the resolved [ChatSenderDisplay].
 *
 * Mirrors the run-grouping coverage in `ChatThreadViewControllerTests`
 * from onym-ios PR #162; on iOS the controller owns this logic, on
 * Android the screen does, but the policy is identical.
 */
class ChatSenderDisplayTest {

    private val alice = "aa".repeat(48)
    private val bob = "bb".repeat(48)

    @Test
    fun runGrouping_headerOnlyAtStartOfSameSenderRun() {
        // Alice, Alice, Bob — header on row 0 (run start) and row 2
        // (sender change), suppressed on row 1 (mid-run).
        val msgs = listOf(
            incoming(alice, "hi", at = 1),
            incoming(alice, "again", at = 2),
            incoming(bob, "yo", at = 3),
        )
        val displays = buildSenderDisplays(msgs, emptyMap())
        assertTrue("run start shows header", displays.getValue(msgs[0].id).showNameHeader)
        assertFalse("mid-run hides header", displays.getValue(msgs[1].id).showNameHeader)
        assertTrue("sender change shows header", displays.getValue(msgs[2].id).showNameHeader)
    }

    @Test
    fun outgoingMessages_neverShowHeader() {
        val msg = outgoing("cc".repeat(48), "mine", at = 1)
        val displays = buildSenderDisplays(listOf(msg), emptyMap())
        assertFalse(
            "own messages are obvious from alignment + color — no header",
            displays.getValue(msg.id).showNameHeader,
        )
    }

    @Test
    fun oneOnOneGroup_suppressesHeader() {
        val msg = incoming("dd".repeat(48), "hey", at = 1, groupType = SepGroupType.ONE_ON_ONE)
        val displays = buildSenderDisplays(listOf(msg), emptyMap())
        assertFalse(
            "1-on-1 chats name no one — there's only one other person",
            displays.getValue(msg.id).showNameHeader,
        )
    }

    @Test
    fun header_usesAliasFromMemberProfiles() {
        val msg = incoming(alice, "hi", at = 1)
        val displays = buildSenderDisplays(listOf(msg), mapOf(alice to profile("Alice")))
        assertEquals("Alice", displays.getValue(msg.id).name)
    }

    @Test
    fun header_fallsBackToFingerprint_whenAliasMissing() {
        // No alias for this sender → short BLS fingerprint fallback.
        val msg = incoming(alice, "hi", at = 1)
        val displays = buildSenderDisplays(listOf(msg), emptyMap())
        assertEquals("BLS " + alice.take(8), displays.getValue(msg.id).name)
    }

    @Test
    fun profileUpdate_refreshesResolvedHeaderName() {
        // A joiner's alias arriving after their message is on screen
        // must repaint the header. The screen re-runs buildSenderDisplays
        // via remember(memberProfiles); here we assert the pure result
        // reflects the new profile.
        val msg = incoming(alice, "hi", at = 1)
        val before = buildSenderDisplays(listOf(msg), emptyMap())
        assertEquals("BLS " + alice.take(8), before.getValue(msg.id).name)

        val after = buildSenderDisplays(listOf(msg), mapOf(alice to profile("Alice")))
        assertEquals(
            "a later profile update must refresh the resolved header name",
            "Alice",
            after.getValue(msg.id).name,
        )
    }

    @Test
    fun accent_isHashedFromPubkey_independentOfAlias() {
        // The accent keys on the pubkey, never the (spoofable) alias:
        // the same sender resolves to the same accent whether named or
        // not, and it equals the standalone hash.
        val msg = incoming(alice, "hi", at = 1)
        val unnamed = buildSenderDisplays(listOf(msg), emptyMap()).getValue(msg.id).accent
        val named = buildSenderDisplays(listOf(msg), mapOf(alice to profile("Alice")))
            .getValue(msg.id).accent
        assertEquals(unnamed, named)
        assertEquals(OnymAccent.forSender(alice), named)
    }

    // ─── Builders ────────────────────────────────────────────────

    private fun profile(alias: String) = MemberProfile(
        alias = alias,
        inboxPublicKey = ByteArray(32) { 1 },
        sendingPubkey = ByteArray(32) { 2 },
    )

    private fun incoming(
        sender: String,
        body: String,
        at: Long,
        groupType: SepGroupType = SepGroupType.TYRANNY,
    ) = ChatMessage(
        id = UUID.randomUUID(),
        groupId = "aa".repeat(32),
        ownerIdentityId = "owner",
        senderBlsPubkeyHex = sender,
        body = body,
        sentAtMillis = 1_700_000_000_000 + at,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.RECEIVED,
        groupType = groupType,
    )

    private fun outgoing(
        sender: String,
        body: String,
        at: Long,
    ) = ChatMessage(
        id = UUID.randomUUID(),
        groupId = "aa".repeat(32),
        ownerIdentityId = "owner",
        senderBlsPubkeyHex = sender,
        body = body,
        sentAtMillis = 1_700_000_000_000 + at,
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.SENT,
        groupType = SepGroupType.TYRANNY,
    )
}
