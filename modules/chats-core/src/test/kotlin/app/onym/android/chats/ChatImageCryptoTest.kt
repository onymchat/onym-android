package app.onym.android.chats

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + integrity tests for [ChatImageCrypto]. Pure JVM — AES/GCM
 * + SHA-256 come from the platform JCE, no Bitmap involved, so these run
 * as a fast unit test rather than an instrumented one.
 *
 * Mirrors the crypto assertions in `ChatImagePipelineTests.swift` from
 * onym-ios. The blob layout (nonce ‖ ciphertext ‖ tag) is byte-compatible
 * with the iOS `AES.GCM.SealedBox.combined` output so a blob sealed on
 * one platform opens on the other.
 */
class ChatImageCryptoTest {

    @Test
    fun sealThenOpen_roundTripsPlaintext() {
        val plaintext = ByteArray(2048) { (it * 7).toByte() }

        val sealed = ChatImageCrypto.seal(plaintext)
        val opened = ChatImageCrypto.open(sealed.blob, sealed.key, sealed.sha256Hex)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun seal_producesFreshKeyAndNoncePerCall() {
        val plaintext = ByteArray(64) { it.toByte() }

        val a = ChatImageCrypto.seal(plaintext)
        val b = ChatImageCrypto.seal(plaintext)

        // Per-image key: two seals of identical bytes never collide.
        assertFalse(a.key.contentEquals(b.key))
        assertFalse(a.blob.contentEquals(b.blob))
        assertFalse(a.sha256Hex == b.sha256Hex)
    }

    @Test
    fun blob_layoutIsNonceCiphertextTag() {
        val plaintext = ByteArray(100) { it.toByte() }

        val sealed = ChatImageCrypto.seal(plaintext)

        // 12-byte nonce + ciphertext (== plaintext length for GCM) + 16-byte tag.
        assertEquals(12 + plaintext.size + 16, sealed.blob.size)
    }

    @Test
    fun sha256Hex_addressesTheCiphertextBlob() {
        val sealed = ChatImageCrypto.seal(ByteArray(32) { it.toByte() })

        // The server addresses blobs by SHA-256 of the stored bytes.
        assertEquals(sealed.sha256Hex, ChatImageCrypto.sha256Hex(sealed.blob))
    }

    @Test
    fun open_rejectsTamperedBlob_viaHashMismatch() {
        val sealed = ChatImageCrypto.seal(ByteArray(64) { it.toByte() })
        val tampered = sealed.blob.copyOf().also { it[20] = (it[20] + 1).toByte() }

        // A flipped byte fails the SHA-256 gate before we ever attempt to
        // decrypt — an attacker can't feed us a substituted blob.
        assertThrows(ChatImageCrypto.HashMismatch::class.java) {
            ChatImageCrypto.open(tampered, sealed.key, sealed.sha256Hex)
        }
    }

    @Test
    fun open_rejectsWrongKey() {
        val sealed = ChatImageCrypto.seal(ByteArray(64) { it.toByte() })
        val wrongKey = ByteArray(32) { 0x7 }

        // Hash still matches (same blob) but GCM auth fails under the wrong
        // key — any exception is fine, the point is it doesn't return bytes.
        var threw = false
        try {
            ChatImageCrypto.open(sealed.blob, wrongKey, sealed.sha256Hex)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }
}
