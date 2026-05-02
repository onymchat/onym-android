package chat.onym.android.identity

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP39 mnemonic generation, validation, and seed derivation +
 * the project-specific HKDF stretches (nostr secp256k1 secret +
 * BLS12-381 secret) layered on top of the BIP39 seed.
 *
 * Ported 1:1 from `onym-ios/Sources/OnymIOS/Identity/Bip39.swift`. The
 * salt / info constants are part of the cross-platform contract — see
 * [IdentityRepository] for the full derivation chain. Any change here
 * MUST be matched in iOS and stellar-mls or recovery phrases stop
 * working across clients.
 *
 * The 2048-word English wordlist lives at
 * `app/src/main/resources/bip39-english.txt` (newline-separated, lower-
 * case, ASCII). SHA-256 of the file equals
 * `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda` —
 * the canonical BIP39 wordlist hash, validated by [Bip39Test].
 */
object Bip39 {

    /** Lazy-loaded from the classpath resource so JVM unit tests work
     *  without an Android `Context`. */
    private val wordlist: List<String> by lazy {
        val resource = Bip39::class.java.classLoader
            ?.getResourceAsStream("bip39-english.txt")
            ?: error("BIP39 wordlist resource missing: bip39-english.txt")
        resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.also {
            check(it.size == 2048) {
                "BIP39 wordlist must contain 2048 words, found ${it.size}"
            }
        }
    }

    /** Index lookup for `entropyFromMnemonic`. Built once at first access. */
    private val wordIndex: Map<String, Int> by lazy {
        wordlist.withIndex().associate { (i, w) -> w to i }
    }

    /** Generate a new 12-word BIP39 mnemonic from 128 bits of CSPRNG entropy. */
    fun generateMnemonic(): String {
        val entropy = ByteArray(16)
        SecureRandom().nextBytes(entropy)
        return mnemonicFromEntropy(entropy)
    }

    /**
     * Convert raw entropy bytes to a BIP39 mnemonic string.
     * Supports 128-bit (12 words) and 256-bit (24 words) entropy.
     */
    fun mnemonicFromEntropy(entropy: ByteArray): String {
        require(entropy.size == 16 || entropy.size == 32) {
            "BIP39 entropy must be 16 or 32 bytes, got ${entropy.size}"
        }
        val hash = sha256(entropy)
        val checksumByte = hash[0].toInt() and 0xFF
        val checksumBits = entropy.size / 4 // ENT / 32

        // Build bit array: entropy bits + checksum bits
        val bits = BooleanArray(entropy.size * 8 + checksumBits)
        var idx = 0
        for (b in entropy) {
            val v = b.toInt() and 0xFF
            for (i in 7 downTo 0) {
                bits[idx++] = ((v shr i) and 1) == 1
            }
        }
        for (i in 0 until checksumBits) {
            val shift = 7 - i
            bits[idx++] = ((checksumByte shr shift) and 1) == 1
        }

        // Split into 11-bit groups and map to words
        val wordCount = bits.size / 11
        val words = ArrayList<String>(wordCount)
        for (w in 0 until wordCount) {
            var index = 0
            for (j in 0 until 11) {
                if (bits[w * 11 + j]) {
                    index = index or (1 shl (10 - j))
                }
            }
            words.add(wordlist[index])
        }
        return words.joinToString(" ")
    }

    /**
     * Convert a BIP39 mnemonic back to the original entropy bytes.
     * Returns `null` if the mnemonic is invalid (bad words or checksum).
     */
    fun entropyFromMnemonic(mnemonic: String): ByteArray? {
        val words = mnemonic.lowercase().split(" ").filter { it.isNotEmpty() }
        if (words.size != 12 && words.size != 24) return null

        val bits = BooleanArray(words.size * 11)
        var idx = 0
        for (word in words) {
            val index = wordIndex[word] ?: return null
            for (i in 10 downTo 0) {
                bits[idx++] = ((index shr i) and 1) == 1
            }
        }

        val checksumBits = words.size / 3 // 4 for 12 words, 8 for 24 words
        val entropyBits = bits.size - checksumBits

        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropy.size) {
            var byte = 0
            for (j in 0 until 8) {
                if (bits[i * 8 + j]) {
                    byte = byte or (1 shl (7 - j))
                }
            }
            entropy[i] = byte.toByte()
        }

        // Verify checksum
        val hash = sha256(entropy)
        val checksumByte = hash[0].toInt() and 0xFF
        for (i in 0 until checksumBits) {
            val expected = ((checksumByte shr (7 - i)) and 1) == 1
            if (bits[entropyBits + i] != expected) return null
        }
        return entropy
    }

    /** Validate a BIP39 mnemonic (word count, known words, checksum). */
    fun isValidMnemonic(mnemonic: String): Boolean = entropyFromMnemonic(mnemonic) != null

    /** Whether a word exists in the BIP39 English wordlist (case-insensitive). */
    fun isKnownWord(word: String): Boolean = wordIndex.containsKey(word.lowercase())

    /** Prefix-match BIP39 words. Empty prefix returns `[]`. */
    fun suggestions(prefix: String, limit: Int = 4): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        val out = ArrayList<String>(limit)
        for (word in wordlist) {
            if (word.startsWith(p)) {
                out.add(word)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * Derive a 64-byte seed from a BIP39 mnemonic via PBKDF2-HMAC-SHA512.
     * Standard BIP39 seed derivation: 2048 iterations, salt =
     * `"mnemonic" + passphrase`. Both inputs are NFKD-normalized per
     * the BIP39 spec — for the all-ASCII wordlist + empty passphrase
     * (this app's default) NFKD is a no-op, but a future custom
     * passphrase containing non-ASCII characters would otherwise
     * produce different bytes than iOS/stellar-mls.
     */
    fun seedFromMnemonic(mnemonic: String, passphrase: String = ""): ByteArray {
        val password = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
            .toByteArray(Charsets.UTF_8)

        val spec = PBEKeySpec(password.toCharArray(), salt, 2048, 64 * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Derive the 32-byte Nostr secp256k1 secret key from a BIP39 seed.
     *
     *   HKDF-SHA256(seed, salt="chat.onym.bip39", info="nostr-secp256k1-v1", L=32)
     *
     * **Salt and info are part of the cross-platform contract** — must
     * match iOS `Bip39.deriveNostrKey` and stellar-mls `KeyManager`.
     */
    fun deriveNostrKey(seed: ByteArray): ByteArray =
        hkdfSha256(
            ikm = seed,
            salt = "chat.onym.bip39".toByteArray(Charsets.UTF_8),
            info = "nostr-secp256k1-v1".toByteArray(Charsets.UTF_8),
            length = 32,
        )

    /**
     * Derive the 32-byte BLS12-381 Fr scalar secret key from a BIP39 seed.
     *
     *   HKDF-SHA256(seed, salt="chat.onym.bip39", info="bls12-381-v1", L=32)
     *
     * **Salt and info are part of the cross-platform contract.**
     */
    fun deriveBlsKey(seed: ByteArray): ByteArray =
        hkdfSha256(
            ikm = seed,
            salt = "chat.onym.bip39".toByteArray(Charsets.UTF_8),
            info = "bls12-381-v1".toByteArray(Charsets.UTF_8),
            length = 32,
        )

    /** HKDF-SHA256 (RFC 5869). Used by both BIP39-seed and nostr-secret derivations. */
    internal fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
