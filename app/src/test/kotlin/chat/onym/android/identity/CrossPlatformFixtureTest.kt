package chat.onym.android.identity

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * **Cross-platform interop fixture.** Locks in derivation against the
 * canonical BIP39 test mnemonic so any change to a salt / info string
 * (HKDF for nostr, BLS, Stellar Ed25519, X25519, or the `sep-inbox-v1`
 * SHA-256 tag) breaks this test loudly.
 *
 * Mirrors `IdentityRepositoryTests.test_derivation_matchesCrossPlatformFixture`
 * in onym-ios. A user who restores `abandon × 11 + about` on any
 * platform must land on the same Stellar `G…` account, the same inbox
 * tag, and (post-FFI) the same nostr / BLS pubkeys, otherwise their
 * groups become unreachable.
 *
 * This file covers the project-specific derivation constants (Bip39
 * seed → nostr/BLS secrets → Stellar Ed25519 pubkey → StrKey + X25519
 * pubkey → inbox tag) WITHOUT requiring the OnymSDK FFI. The two
 * FFI-derived public keys (`nostrPublicKey` from secp256k1 schnorr,
 * `blsPublicKey` from BLS12-381 G1 multiply) are checked separately
 * in the instrumented test
 * (`androidTest/IdentityRepositoryTest.test_derivation_matchesCrossPlatformFixture`)
 * because they need the JNI cdylib loaded — only the Android emulator
 * has that, the host JVM doesn't.
 */
class CrossPlatformFixtureTest {

    companion object {
        private const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        // Fixture bytes from onym-ios PR #2's
        // IdentityRepositoryTests.test_derivation_matchesCrossPlatformFixture.
        private const val EXPECTED_STELLAR_PUB_HEX =
            "2d26005ffeaf78d38581e0c1c1cea3a7ae5d9510b0215a122c2b8c7ea24c6118"
        private const val EXPECTED_STELLAR_ACCOUNT_ID =
            "GAWSMAC772XXRU4FQHQMDQOOUOT24XMVCCYCCWQSFQVYY7VCJRQRRF2K"
        private const val EXPECTED_INBOX_PUB_HEX =
            "677244099e153cd18331aa2b44132d82b2a7f385f339b05184ac92df77e79d50"
        private const val EXPECTED_INBOX_TAG =
            "2257fa71222dcc05"

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // BC needs to be a registered provider so HKDFBytesGenerator
            // (used by Bip39) can locate SHA256Digest. In the production
            // app, OnymApplication.onCreate() handles this; in JVM unit
            // tests we wire it up here.
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    @Test
    fun mnemonic_decodes_to_zero_entropy() {
        // abandon × 11 + about is the canonical BIP39 test vector for
        // 16-byte all-zeros entropy. If this fails, our wordlist
        // ordering is wrong (or the wordlist itself is corrupt).
        val entropy = Bip39.entropyFromMnemonic(MNEMONIC)
        assertArrayEquals(ByteArray(16), entropy)
    }

    @Test
    fun pbkdf2_seed_matches_known_bip39_test_vector() {
        // Standard BIP39 test vector for `abandon × 11 + about` with
        // empty passphrase. Sourced from the BIP39 spec test vectors.
        // If this fails, our PBKDF2 setup (NFKD normalization, salt,
        // iteration count, output length) is wrong.
        val expectedSeedHex =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        val seed = Bip39.seedFromMnemonic(MNEMONIC)
        assertEquals(expectedSeedHex, seed.toHex())
    }

    @Test
    fun stellar_pubkey_and_account_id_match_ios() {
        // Derivation chain:
        //   mnemonic → seed → HKDF(seed, "chat.onym.bip39", "nostr-secp256k1-v1") = nostrSecret
        //   nostrSecret → HKDF("chat.onym.ios", "stellar-ed25519-v1") → Ed25519 seed
        //   Ed25519 seed → BC Ed25519PrivateKeyParameters → public 32 bytes
        //   StellarStrKey-encode(pub32) → "G..."
        // Failure here means one of: seed PBKDF2, HKDF salt/info constants,
        // BC Ed25519, or StrKey encoding has drifted from iOS.
        val seed = Bip39.seedFromMnemonic(MNEMONIC)
        val nostrSecret = Bip39.deriveNostrKey(seed)
        val stellarPub = IdentityRepository.stellarPublicKey(nostrSecret)
        assertEquals(EXPECTED_STELLAR_PUB_HEX, stellarPub.toHex())
        assertEquals(EXPECTED_STELLAR_ACCOUNT_ID, StellarStrKey.encodeAccountID(stellarPub))
    }

    @Test
    fun x25519_pubkey_and_inbox_tag_match_ios() {
        // Same chain through the nostrSecret, then:
        //   nostrSecret → HKDF("chat.onym.ios", "x25519-key-agreement-v1") → X25519 seed
        //   X25519 seed → BC X25519PrivateKeyParameters → public 32 bytes
        //   SHA256("sep-inbox-v1" || pub32)[0..8] hex → inbox tag
        val seed = Bip39.seedFromMnemonic(MNEMONIC)
        val nostrSecret = Bip39.deriveNostrKey(seed)
        val inboxPub = IdentityRepository.inboxPublicKey(nostrSecret)
        assertEquals(EXPECTED_INBOX_PUB_HEX, inboxPub.toHex())
        assertEquals(EXPECTED_INBOX_TAG, IdentityRepository.inboxTag(inboxPub))
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
