package chat.onym.android.transport.nostr

import chat.onym.android.transport.TransportInboxId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Covers the pure event-building and filter-shape paths of the
 * inbox adapter. Connection-bearing paths await an integration test
 * layer.
 *
 * Mirrors `NostrInboxTransportTests.swift` from onym-ios PR #13.
 * Uses [DeterministicFakeSigner] for the same JVM-vs-FFI reason as
 * the rest of the framing-test suite.
 */
class NostrInboxTransportTest {

    private val signer = DeterministicFakeSigner(secret = ByteArray(32) { 0xEF.toByte() })

    // ─── buildSendEvent ───────────────────────────────────────────

    @Test
    fun buildSendEvent_usesKind34113() {
        val event = NostrInboxTransport.buildSendEvent(
            payload = ByteArray(0),
            inbox = TransportInboxId("abc123"),
            signer = signer,
        )
        assertEquals(34113, event.kind)
    }

    @Test
    fun buildSendEvent_emitsExpectedTagSet() {
        val event = NostrInboxTransport.buildSendEvent(
            payload = ByteArray(0),
            inbox = TransportInboxId("abc123"),
            signer = signer,
        )
        // Strip the appended ms tag — its value is non-deterministic.
        val userTags = event.tags.filter { it.firstOrNull() != "ms" }
        assertEquals(
            listOf(
                listOf("d", "sep-inbox:abc123"),
                listOf("t", "abc123"),
                listOf("sep_inbox", "abc123"),
                listOf("sep_version", "1"),
            ),
            userTags,
        )
    }

    @Test
    fun buildSendEvent_dTagPrefixIsLoadBearing() {
        // A relay using the parameterised-replaceable `d` tag for
        // routing keys on the literal string — drift on the prefix
        // would silently break delivery.
        val event = NostrInboxTransport.buildSendEvent(
            payload = ByteArray(0),
            inbox = TransportInboxId("id-1"),
            signer = signer,
        )
        val dTag = event.tags.firstOrNull { it.firstOrNull() == "d" }
        assertNotNull(dTag)
        assertEquals("sep-inbox:id-1", dTag!![1])
    }

    @Test
    fun buildSendEvent_payloadRoundtripsViaBase64() {
        val payload = "invitation-blob".toByteArray()
        val event = NostrInboxTransport.buildSendEvent(
            payload = payload,
            inbox = TransportInboxId("x"),
            signer = signer,
        )
        assertTrue(payload.contentEquals(Base64.getDecoder().decode(event.content)))
    }

    @Test
    fun buildSendEvent_eventIdIsValid() {
        val event = NostrInboxTransport.buildSendEvent(
            payload = "x".toByteArray(),
            inbox = TransportInboxId("x"),
            signer = signer,
        )
        assertTrue(event.verifyEventId())
    }

    // ─── subscriptionFilters ──────────────────────────────────────

    @Test
    fun subscriptionFilters_returnsThreeShapes() {
        val filters = NostrInboxTransport.subscriptionFilters("id-1")
        assertEquals(
            "primary #d + secondary #t + legacy kind = 3 filters",
            3,
            filters.size,
        )
    }

    @Test
    fun subscriptionFilters_primaryUsesDTagWithPrefix() {
        val filter = NostrInboxTransport.subscriptionFilters("id-1")[0]
        val kinds = filter.getJSONArray("kinds")
        val dValues = filter.getJSONArray("#d")
        assertEquals(1, kinds.length())
        assertEquals(34113, kinds.getInt(0))
        assertEquals(1, dValues.length())
        assertEquals("sep-inbox:id-1", dValues.getString(0))
    }

    @Test
    fun subscriptionFilters_secondaryUsesTTagOnPrimaryKind() {
        val filter = NostrInboxTransport.subscriptionFilters("id-1")[1]
        val kinds = filter.getJSONArray("kinds")
        val tValues = filter.getJSONArray("#t")
        assertEquals(1, kinds.length())
        assertEquals(34113, kinds.getInt(0))
        assertEquals(1, tValues.length())
        assertEquals("id-1", tValues.getString(0))
    }

    @Test
    fun subscriptionFilters_legacyUsesTTagOnLegacyKind() {
        val filter = NostrInboxTransport.subscriptionFilters("id-1")[2]
        val kinds = filter.getJSONArray("kinds")
        val tValues = filter.getJSONArray("#t")
        assertEquals(1, kinds.length())
        assertEquals(24113, kinds.getInt(0))
        assertEquals(1, tValues.length())
        assertEquals("id-1", tValues.getString(0))
    }

    private class DeterministicFakeSigner(private val secret: ByteArray) : NostrSigner {
        override fun publicKey(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret)
        override fun signEventId(eventId: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(secret + eventId).copyOf(64)
    }
}
