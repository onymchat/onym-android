package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.group.MemberProfile
import app.onym.android.group.OnymAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.UUID
import org.junit.Test

/**
 * Pin for the live reply-quote resolution
 * ([resolveReplyQuote] / [buildReplyQuotes]). The quote is resolved
 * against the loaded message list — never snapshotted on the wire —
 * so a target that isn't present degrades to "Message unavailable".
 *
 * Mirrors the `replyQuote(for:)` controller tests from onym-ios PR #174.
 */
class ChatReplyQuoteTest {

    private val aliceBls = "aa".repeat(48)
    private val bobBls = "cc".repeat(48)
    private val profiles = mapOf(
        aliceBls to MemberProfile(
            alias = "Alice",
            inboxPublicKey = ByteArray(32) { 0x22 },
            sendingPubkey = ByteArray(32) { 0x33 },
        ),
    )

    @Test
    fun resolve_nonReply_isNull() {
        val message = message(sender = bobBls, body = "hi", replyTo = null)
        assertNull(resolveReplyQuote(message, mapOf(message.id to message), profiles))
    }

    @Test
    fun resolve_foundTarget_carriesSenderNameSnippetAndAccent() {
        val original = message(sender = aliceBls, body = "the original")
        val reply = message(sender = bobBls, body = "agreed", replyTo = original.id)
        val byId = mapOf(original.id to original, reply.id to reply)

        val quote = resolveReplyQuote(reply, byId, profiles)!!
        assertFalse(quote.isUnavailable)
        assertEquals("Alice", quote.name)
        assertEquals("the original", quote.snippet)
        // Accent tracks the *quoted* sender (Alice), not the replier.
        assertEquals(OnymAccent.forSender(aliceBls), quote.accent)
    }

    @Test
    fun resolve_unknownTarget_isUnavailable() {
        val reply = message(sender = bobBls, body = "agreed", replyTo = UUID.randomUUID())
        val quote = resolveReplyQuote(reply, mapOf(reply.id to reply), profiles)!!
        assertTrue("a reply to a message we don't have is unavailable", quote.isUnavailable)
    }

    @Test
    fun resolve_targetSenderWithoutAlias_fallsBackToFingerprint() {
        // Bob has no profile entry → name falls back to the BLS
        // fingerprint, same as the members list.
        val original = message(sender = bobBls, body = "no alias here")
        val reply = message(sender = aliceBls, body = "ok", replyTo = original.id)
        val byId = mapOf(original.id to original, reply.id to reply)

        val quote = resolveReplyQuote(reply, byId, profiles)!!
        assertEquals("BLS " + bobBls.take(8), quote.name)
    }

    @Test
    fun buildReplyQuotes_onlyIncludesReplies() {
        val original = message(sender = aliceBls, body = "first")
        val reply = message(sender = bobBls, body = "second", replyTo = original.id)
        val plain = message(sender = aliceBls, body = "third")

        val quotes = buildReplyQuotes(listOf(original, reply, plain), profiles)
        assertEquals("only the reply gets a quote", setOf(reply.id), quotes.keys)
        assertEquals("first", quotes[reply.id]!!.snippet)
    }

    private fun message(
        sender: String,
        body: String,
        replyTo: UUID? = null,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = "aa".repeat(32),
        ownerIdentityId = "owner",
        senderBlsPubkeyHex = sender,
        body = body,
        sentAtMillis = 1_700_000_000_000L,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.RECEIVED,
        replyToMessageId = replyTo,
        groupType = SepGroupType.TYRANNY,
    )
}
