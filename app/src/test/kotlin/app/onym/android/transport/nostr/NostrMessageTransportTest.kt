package app.onym.android.transport.nostr

import app.onym.android.transport.TransportTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Covers the pure event-building path of the broadcast adapter. The
 * connection / publish / subscribe paths require a real or fake
 * WebSocket server and are deferred to integration tests.
 *
 * Mirrors `NostrMessageTransportTests.swift` from onym-ios PR #13.
 * Uses [DeterministicFakeSigner] instead of the OnymSDK-backed
 * signer for the same JVM-vs-FFI reason as [NostrEventTest] — see
 * the doc on that class for the rationale.
 */
class NostrMessageTransportTest {

    private val signer = DeterministicFakeSigner(secret = ByteArray(32) { 0xCD.toByte() })

    @Test
    fun buildPublishEvent_usesKind44114() {
        val event = NostrMessageTransport.buildPublishEvent(
            payload = "hello".toByteArray(),
            topic = TransportTopic("topic-a"),
            signer = signer,
        )
        assertEquals(44114, event.kind)
    }

    @Test
    fun buildPublishEvent_emitsTopicTag() {
        val event = NostrMessageTransport.buildPublishEvent(
            payload = ByteArray(0),
            topic = TransportTopic("topic-a"),
            signer = signer,
        )
        val tTags = event.tags.filter { it.firstOrNull() == "t" }
        assertEquals(
            "publish must emit exactly one [t, topic] tag",
            listOf(listOf("t", "topic-a")),
            tTags,
        )
    }

    @Test
    fun buildPublishEvent_appendsMsTag() {
        val event = NostrMessageTransport.buildPublishEvent(
            payload = ByteArray(0),
            topic = TransportTopic("x"),
            signer = signer,
        )
        assertNotNull(event.tags.firstOrNull { it.firstOrNull() == "ms" })
    }

    @Test
    fun buildPublishEvent_payloadRoundtripsViaBase64() {
        val payload = ByteArray(256) { it.toByte() }
        val event = NostrMessageTransport.buildPublishEvent(
            payload = payload,
            topic = TransportTopic("x"),
            signer = signer,
        )
        val decoded = Base64.getDecoder().decode(event.content)
        assertTrue(payload.contentEquals(decoded))
    }

    @Test
    fun buildPublishEvent_emptyPayloadProducesEmptyBase64() {
        val event = NostrMessageTransport.buildPublishEvent(
            payload = ByteArray(0),
            topic = TransportTopic("x"),
            signer = signer,
        )
        assertEquals("", event.content)
    }

    @Test
    fun buildPublishEvent_eventIdIsValid() {
        val event = NostrMessageTransport.buildPublishEvent(
            payload = "hello".toByteArray(),
            topic = TransportTopic("topic-a"),
            signer = signer,
        )
        assertTrue(event.verifyEventId())
    }

    @Test
    fun buildPublishEvent_distinctSignersProduceDifferentPubkeys() {
        // Use two distinct fake signers (different secrets → different
        // SHA-256 pubkeys). On real BIP340 + ephemeral SecureRandom
        // the property holds with overwhelming probability; covered
        // end-to-end in the instrumented OnymNostrSignerTest.
        val signerA = DeterministicFakeSigner(secret = ByteArray(32) { 0x01 })
        val signerB = DeterministicFakeSigner(secret = ByteArray(32) { 0x02 })
        val topic = TransportTopic("x")
        val eventA = NostrMessageTransport.buildPublishEvent(ByteArray(0), topic, signerA)
        val eventB = NostrMessageTransport.buildPublishEvent(ByteArray(0), topic, signerB)
        assertNotEquals(
            "ephemeral signing is the metadata-hiding property — pubkeys must differ",
            eventA.pubkey,
            eventB.pubkey,
        )
    }

    private class DeterministicFakeSigner(private val secret: ByteArray) : NostrSigner {
        override fun publicKey(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret)
        override fun signEventId(eventId: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(secret + eventId).copyOf(64)
    }
}
