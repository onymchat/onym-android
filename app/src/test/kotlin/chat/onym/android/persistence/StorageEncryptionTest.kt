package chat.onym.android.persistence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM tests for the AES-GCM crypto primitive. Skips the
 * Keystore-backed loader path (covered by `androidTest/` reading
 * back from a real device) — instead instantiates with a known
 * 32-byte key per test for deterministic assertions.
 *
 * Mirrors `StorageEncryptionTests.swift` from onym-ios PR #16.
 */
class StorageEncryptionTest {

    private lateinit var enc: StorageEncryption
    private lateinit var keyBytes: ByteArray

    @Before
    fun setUp() {
        keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        enc = StorageEncryption(SecretKeySpec(keyBytes, "AES"))
    }

    // ─── roundtrip ────────────────────────────────────────────────

    @Test
    fun roundtrip_byteArray() {
        val plaintext = "hello, onym".toByteArray(Charsets.UTF_8)
        val ciphertext = enc.encrypt(plaintext)
        assertArrayEquals(plaintext, enc.decrypt(ciphertext))
    }

    @Test
    fun roundtrip_string() {
        val plaintext = "hello, onym — кириллица + emoji 🔐"
        val ciphertext = enc.encrypt(plaintext)
        assertEquals(plaintext, enc.decryptString(ciphertext))
    }

    @Test
    fun roundtrip_emptyPayload() {
        val ciphertext = enc.encrypt(ByteArray(0))
        // GCM still emits nonce + 16-byte tag for an empty plaintext.
        assertEquals(StorageEncryption.NONCE_SIZE + StorageEncryption.TAG_SIZE, ciphertext.size)
        assertArrayEquals(ByteArray(0), enc.decrypt(ciphertext))
    }

    // ─── layout ───────────────────────────────────────────────────

    @Test
    fun ciphertext_layout_is_nonce_then_ciphertextWithTag() {
        val plaintext = ByteArray(100) { it.toByte() }
        val ciphertext = enc.encrypt(plaintext)
        // 12B nonce + 100B body + 16B tag = 128B
        assertEquals(
            StorageEncryption.NONCE_SIZE + plaintext.size + StorageEncryption.TAG_SIZE,
            ciphertext.size,
        )
    }

    @Test
    fun nonce_is_unique_per_encryption() {
        val plaintext = "same plaintext".toByteArray(Charsets.UTF_8)
        val a = enc.encrypt(plaintext)
        val b = enc.encrypt(plaintext)
        // Identical plaintext + key + AES/GCM/NoPadding must produce
        // distinct outputs (because the nonce is fresh per call). If
        // the nonces collide the entire ciphertext would be identical
        // — and re-using a GCM nonce is catastrophic. Assert on the
        // first NONCE_SIZE bytes specifically.
        val nonceA = a.copyOfRange(0, StorageEncryption.NONCE_SIZE)
        val nonceB = b.copyOfRange(0, StorageEncryption.NONCE_SIZE)
        assertFalse(
            "GCM nonce reuse across two encrypt() calls — fatal for confidentiality",
            nonceA.contentEquals(nonceB),
        )
        assertNotEquals(
            "encrypt() of the same plaintext twice must produce different ciphertexts",
            a.toList(),
            b.toList(),
        )
    }

    // ─── tamper rejection ─────────────────────────────────────────

    @Test
    fun tamper_rejection_singleBitFlip() {
        val ciphertext = enc.encrypt("don't tamper with me".toByteArray(Charsets.UTF_8))
        // Flip a bit in the body (past the nonce so we can be sure
        // we're hitting AES-GCM's tag check, not just the IV).
        val tampered = ciphertext.copyOf().also { it[StorageEncryption.NONCE_SIZE + 2] = (it[StorageEncryption.NONCE_SIZE + 2].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) { enc.decrypt(tampered) }
    }

    @Test
    fun tamper_rejection_truncatedTag() {
        val ciphertext = enc.encrypt("don't tamper with me".toByteArray(Charsets.UTF_8))
        // Drop the last byte of the tag — auth check must fail
        // (either AEADBadTagException or one of its callers).
        val truncated = ciphertext.copyOfRange(0, ciphertext.size - 1)
        // GCM may surface the failure as AEADBadTagException OR
        // IllegalBlockSizeException(cause = AEADBadTagException) —
        // accept either as long as some throw happens.
        var threw = false
        try { enc.decrypt(truncated) } catch (_: Throwable) { threw = true }
        assertTrue("truncating the tag must fail decryption", threw)
    }

    @Test
    fun key_stability_across_encrypt_calls() {
        // Property: a single instance encrypts with a stable key — so
        // ciphertexts from any call decrypt back to the same plaintext
        // through the same instance. (The Keystore-backed loader
        // produces the same key bytes across process restarts; covered
        // separately via androidTest.)
        val a = enc.encrypt("a".toByteArray())
        val b = enc.encrypt("b".toByteArray())
        val c = enc.encrypt("c".toByteArray())
        assertArrayEquals("a".toByteArray(), enc.decrypt(a))
        assertArrayEquals("b".toByteArray(), enc.decrypt(b))
        assertArrayEquals("c".toByteArray(), enc.decrypt(c))
    }
}
