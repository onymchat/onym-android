package app.onym.android.identity

import app.onym.android.foundation.Bip39
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.util.UUID

/**
 * [IdentityId.derivedFromEntropy] in isolation — pure, no store, no
 * FFI, so it runs on the host JVM where CI can see it.
 *
 * The cross-platform half of the contract (this phrase produces this
 * exact id on iOS too) is pinned in [CrossPlatformFixtureTest],
 * alongside the other salt/info constants it has to stay consistent
 * with.
 */
class IdentityIdTest {

    companion object {
        private const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val OTHER_MNEMONIC =
            "legal winner thank year wave sausage worth useful legal winner thank yellow"

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // Same reason as CrossPlatformFixtureTest: HKDFBytesGenerator
            // needs BC registered, which OnymApplication.onCreate() does
            // in the real app and nothing does in a host-JVM test.
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }

        private fun entropy(mnemonic: String): ByteArray =
            Bip39.entropyFromMnemonic(mnemonic) ?: error("fixture mnemonic must be valid")
    }

    /** The whole point: a recovery phrase that produced identity A has
     *  to keep producing identity A. Chats and messages are
     *  owner-scoped, so an id that changes on import is an archive
     *  nobody can see. */
    @Test
    fun sameEntropy_derivesSameId() {
        assertEquals(
            "the same recovery phrase must always name the same identity",
            IdentityId.derivedFromEntropy(entropy(MNEMONIC)),
            IdentityId.derivedFromEntropy(entropy(MNEMONIC)),
        )
    }

    @Test
    fun differentEntropy_derivesDifferentId() {
        assertNotEquals(
            "two phrases must not collide onto one identity slot",
            IdentityId.derivedFromEntropy(entropy(MNEMONIC)),
            IdentityId.derivedFromEntropy(entropy(OTHER_MNEMONIC)),
        )
    }

    /** A one-bit change must not leave the id recognisably close to the
     *  original. The id lands in plaintext group and message rows while
     *  the same entropy roots the Nostr and BLS secrets, so "adjacent
     *  seeds look adjacent" would make it a hint about key material. */
    @Test
    fun oneBitFlipInEntropy_derivesDifferentId() {
        val original = IdentityId.derivedFromEntropy(entropy(MNEMONIC))
        val flipped = entropy(MNEMONIC).also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        assertNotEquals(original, IdentityId.derivedFromEntropy(flipped))
    }

    /**
     * The derived value is stored as a SharedPreferences key suffix, a
     * group owner stamp and a message owner column, and is read back as
     * an opaque string. Assert it is nonetheless a well-formed UUID —
     * the bit-twiddling is the kind of thing that fails silently and
     * only shows up wherever something finally tries to parse it.
     */
    @Test
    fun derivedId_isWellFormedUuidAndRoundTrips() {
        val id = IdentityId.derivedFromEntropy(entropy(MNEMONIC))

        val parsed = UUID.fromString(id.value)
        assertEquals(id, IdentityId(parsed.toString().uppercase()))

        // Stamped v4 + RFC 4122 variant so a derived id is
        // indistinguishable from a legacy random one everywhere it is
        // stored — see the rejection of RFC 9562 v8 in
        // IdentityId.derivedFromEntropy's doc.
        assertEquals("version nibble must say 4", 4, parsed.version())
        assertEquals("variant bits must say RFC 4122", 2, parsed.variant())
    }

    /** iOS renders `UUID.uuidString` upper-case and the archive's owner
     *  stamp is compared as a string, so the casing is part of the
     *  contract, not a rendering detail. Java's own `UUID.toString()`
     *  is lower-case, which is exactly the trap. */
    @Test
    fun derivedId_isUpperCaseToMatchIos() {
        val value = IdentityId.derivedFromEntropy(entropy(MNEMONIC)).value
        assertEquals(value.uppercase(), value)
    }

    /** `new()` stays for tests that want an id this device has never
     *  seen. It must not accidentally become deterministic. */
    @Test
    fun new_staysRandom() {
        assertNotEquals(IdentityId.new(), IdentityId.new())
    }
}
