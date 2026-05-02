package chat.onym.android.identity

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest
import java.security.Security

/**
 * Pure-logic unit tests for [Bip39]. The cross-platform fixture lives in
 * [CrossPlatformFixtureTest]; this file covers wordlist integrity,
 * mnemonic round-trip, validation rules, and suggestion behaviour.
 */
class Bip39Test {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    @Test
    fun wordlist_has_2048_words() {
        // Indirect check via mnemonicFromEntropy — last word index 0x07FF
        // (= 2047) requires exactly 2048-word lookup table.
        val mnemonic = Bip39.mnemonicFromEntropy(ByteArray(16) { 0xFF.toByte() })
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        // All-FF entropy: top 11 bits = 0x7FF → last index 2047 → "zoo"
        assertEquals("zoo", words[0])
    }

    @Test
    fun wordlist_resource_sha256_matches_canonical_bip39() {
        // Validates the wordlist FILE is the canonical BIP39 English
        // list (newline-separated, trailing newline). If this fails,
        // the resource was tampered with or has whitespace drift.
        val resource = Bip39::class.java.classLoader
            ?.getResourceAsStream("bip39-english.txt")
            ?: error("missing resource")
        val bytes = resource.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = sha.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        // SHA-256 of the canonical BIP39 English wordlist with one
        // trailing newline.
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            hex,
        )
    }

    @Test
    fun mnemonic_round_trip() {
        // Random-looking entropy → mnemonic → entropy must be identity.
        val entropy = ByteArray(16) { (it * 17 + 5).toByte() }
        val mnemonic = Bip39.mnemonicFromEntropy(entropy)
        val recovered = Bip39.entropyFromMnemonic(mnemonic)
        assertNotNull(recovered)
        assertEquals(entropy.toList(), recovered!!.toList())
    }

    @Test
    fun mnemonic_rejects_invalid_word_count() {
        assertNull(Bip39.entropyFromMnemonic("abandon"))
        assertNull(Bip39.entropyFromMnemonic("abandon abandon"))
        assertFalse(Bip39.isValidMnemonic("abandon abandon"))
    }

    @Test
    fun mnemonic_rejects_unknown_word() {
        // "notaword" isn't in the wordlist — entropyFromMnemonic returns null.
        val invalid = "notaword abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertNull(Bip39.entropyFromMnemonic(invalid))
        assertFalse(Bip39.isValidMnemonic(invalid))
    }

    @Test
    fun mnemonic_rejects_bad_checksum() {
        // abandon × 12 has a bad checksum (the "about" final word is
        // what makes the all-zeros entropy mnemonic valid).
        assertNull(Bip39.entropyFromMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"))
    }

    @Test
    fun isKnownWord_handles_case() {
        assertTrue(Bip39.isKnownWord("abandon"))
        assertTrue(Bip39.isKnownWord("ABANDON"))
        assertFalse(Bip39.isKnownWord("notaword"))
    }

    @Test
    fun suggestions_prefix_match() {
        val s = Bip39.suggestions("aban", limit = 4)
        assertTrue("expected 'abandon' in $s", s.contains("abandon"))
        assertTrue(s.size <= 4)
    }

    @Test
    fun suggestions_empty_prefix_returns_empty() {
        assertTrue(Bip39.suggestions("").isEmpty())
    }

    @Test
    fun generateMnemonic_produces_12_words_and_round_trips() {
        repeat(5) {
            val m = Bip39.generateMnemonic()
            assertEquals(12, m.split(" ").size)
            assertTrue(Bip39.isValidMnemonic(m))
        }
    }
}
