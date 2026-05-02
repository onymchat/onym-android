package chat.onym.android.transport.nostr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Tests for the NIP-01 wire format and the integrity check that
 * [NostrRelayConnection] runs on every inbound event. These are the
 * only invariants that protect us from relay-side tampering, so the
 * coverage is deliberately blunt: build → mutate one byte → verify
 * fails.
 *
 * Mirrors `NostrEventTests.swift` from onym-ios PR #13. iOS uses the
 * real OnymSDK-backed signer because Swift Package + XCFramework
 * loads the FFI directly into XCTest. On Android the OnymSDK FFI
 * lives in jni/abi/lib*.so inside the AAR — only loadable on
 * emulator/device, not on host JVM. So this JVM-unit test uses a
 * deterministic [DeterministicFakeSigner] (pubkey = SHA-256(secret),
 * sig = SHA-512(secret || eventId).take(64)) instead. The framing
 * assertions don't depend on real BIP340; the signer-FFI roundtrip
 * is covered separately in [androidTest's OnymNostrSignerTest]
 * (instrumented).
 */
class NostrEventTest {

    private val signer = DeterministicFakeSigner(secret = ByteArray(32) { 0xAB.toByte() })

    // ─── build ─────────────────────────────────────────────────────

    @Test
    fun build_producesValidEventId() {
        val event = NostrEvent.build(
            kind = 44114,
            tags = listOf(listOf("t", "topic-x")),
            content = "hello",
            signer = signer,
        )
        assertTrue("freshly built event must verify", event.verifyEventId())
    }

    @Test
    fun build_appendsMillisecondTag() {
        val event = NostrEvent.build(
            kind = 44114,
            tags = listOf(listOf("t", "topic-x")),
            content = "hello",
            signer = signer,
        )
        val msTag = event.tags.firstOrNull { it.firstOrNull() == "ms" }
        assertNotNull("build() must append [\"ms\", ...]", msTag)
        assertEquals(2, msTag!!.size)
        val ms = msTag[1].toLongOrNull()
        assertNotNull(ms)
        // Within 5s of "now" — sanity, not exact equality.
        val nowMs = System.currentTimeMillis()
        assertTrue("ms within 5s of now (got $ms vs $nowMs)", kotlin.math.abs(ms!! - nowMs) < 5_000)
    }

    @Test
    fun build_signatureIsHex128Chars() {
        val event = NostrEvent.build(kind = 44114, tags = emptyList(), content = "x", signer = signer)
        // BIP340 sig is 64 bytes = 128 hex chars. The fake signer
        // returns 64 bytes too, so this asserts the framing/encoding
        // path — not BIP340 validity.
        assertEquals(128, event.sig.length)
    }

    @Test
    fun build_pubkeyMatchesSigner() {
        val event = NostrEvent.build(kind = 44114, tags = emptyList(), content = "x", signer = signer)
        val expectedPubkeyHex = signer.publicKey().toHex()
        assertEquals(expectedPubkeyHex, event.pubkey)
    }

    @Test
    fun build_preservesCallerProvidedTags() {
        val userTags = listOf(listOf("t", "topic-x"), listOf("sep_version", "1"))
        val event = NostrEvent.build(
            kind = 34113,
            tags = userTags,
            content = "x",
            signer = signer,
        )
        // Caller tags appear first, then the appended ms tag.
        assertEquals(userTags, event.tags.take(userTags.size))
    }

    // ─── verifyEventId tampering ──────────────────────────────────

    @Test
    fun verifyEventId_rejectsTamperedContent() {
        val event = NostrEvent.build(kind = 44114, tags = emptyList(), content = "hello", signer = signer)
        val tampered = event.copy(content = "goodbye")
        assertFalse(tampered.verifyEventId())
    }

    @Test
    fun verifyEventId_rejectsTamperedTags() {
        val event = NostrEvent.build(kind = 44114, tags = listOf(listOf("t", "a")), content = "x", signer = signer)
        val tampered = event.copy(
            tags = listOf(listOf("t", "b")) + event.tags.drop(1),
        )
        assertFalse(tampered.verifyEventId())
    }

    @Test
    fun verifyEventId_rejectsTamperedKind() {
        val event = NostrEvent.build(kind = 44114, tags = emptyList(), content = "x", signer = signer)
        val tampered = event.copy(kind = 1)
        assertFalse(tampered.verifyEventId())
    }

    // ─── displayMilliseconds ──────────────────────────────────────

    @Test
    fun displayMilliseconds_readsMsTag() {
        val event = NostrEvent(
            id = "00", pubkey = "00", createdAt = 1_700_000_000L,
            kind = 44114, tags = listOf(listOf("ms", "1700000000123")),
            content = "", sig = "",
        )
        assertEquals(1_700_000_000_123L, event.displayMilliseconds)
    }

    @Test
    fun displayMilliseconds_fallsBackToCreatedAt() {
        val event = NostrEvent(
            id = "00", pubkey = "00", createdAt = 1_700_000_000L,
            kind = 44114, tags = emptyList(),
            content = "", sig = "",
        )
        assertEquals(1_700_000_000_000L, event.displayMilliseconds)
    }

    @Test
    fun displayMilliseconds_ignoresMalformedMsTag() {
        val event = NostrEvent(
            id = "00", pubkey = "00", createdAt = 1_700_000_000L,
            kind = 44114, tags = listOf(listOf("ms", "not-a-number")),
            content = "", sig = "",
        )
        assertEquals(1_700_000_000_000L, event.displayMilliseconds)
    }

    @Test
    fun displayMilliseconds_ignoresNegativeMs() {
        val event = NostrEvent(
            id = "00", pubkey = "00", createdAt = 1_700_000_000L,
            kind = 44114, tags = listOf(listOf("ms", "-1")),
            content = "", sig = "",
        )
        assertEquals(1_700_000_000_000L, event.displayMilliseconds)
    }

    // ─── wire format ──────────────────────────────────────────────

    @Test
    fun toJson_usesCreatedAtFieldName() {
        val event = NostrEvent(
            id = "deadbeef", pubkey = "abc", createdAt = 42L,
            kind = 1, tags = listOf(listOf("t", "x")), content = "hi", sig = "sig",
        )
        val json = event.toJson()
        assertEquals(42L, json.getLong("created_at"))
        // Wire field is `created_at`, not `createdAt`.
        assertFalse(json.has("createdAt"))
    }

    @Test
    fun toJson_fromJson_roundtrip() {
        val original = NostrEvent(
            id = "deadbeef", pubkey = "abc", createdAt = 42L,
            kind = 1,
            tags = listOf(listOf("t", "x"), listOf("ms", "42000")),
            content = "hi", sig = "sig",
        )
        val decoded = NostrEvent.fromJson(JSONObject(original.toJson().toString()))
        assertNotNull(decoded)
        assertEquals(original.id, decoded!!.id)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.tags, decoded.tags)
        assertEquals(original.content, decoded.content)
    }

    // ─── Fakes ────────────────────────────────────────────────────

    /**
     * Deterministic, no-FFI signer that lets framing tests run on
     * host JVM. Pubkey = SHA-256(secret), sig = first 64 bytes of
     * SHA-512(secret || eventId). Both transforms are stable and
     * differ between distinct secrets, which is enough to exercise
     * the [NostrEvent.build] paths.
     *
     * NOT BIP340-compatible — the signer-FFI roundtrip is covered
     * by `androidTest/.../OnymNostrSignerTest` against the real
     * `chat.onym.sdk.Common` on an emulator.
     */
    private class DeterministicFakeSigner(private val secret: ByteArray) : NostrSigner {
        override fun publicKey(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret)

        override fun signEventId(eventId: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512")
                .digest(secret + eventId)
                .copyOf(64)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
