package chat.onym.android.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StellarStrKeyTest {

    @Test
    fun encodes_zero_pubkey_to_known_account_id() {
        // 32-zero Ed25519 pubkey → version=48 → CRC16-XMODEM(payload) →
        // base32 (RFC4648 no padding) →
        // "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        // Computed independently with the StrKey reference algorithm.
        val encoded = StellarStrKey.encodeAccountID(ByteArray(32))
        assertEquals(56, encoded.length)
        assertEquals('G', encoded[0])
        assertEquals(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF",
            encoded,
        )
    }

    @Test
    fun encodes_all_ff_pubkey_to_known_account_id() {
        // 32 0xFF bytes → known StrKey via the same reference algorithm.
        val encoded = StellarStrKey.encodeAccountID(ByteArray(32) { 0xFF.toByte() })
        assertEquals(
            "GD7777777777777777777777777777777777777777777777777773DB",
            encoded,
        )
    }

    @Test
    fun encoded_account_id_is_56_chars() {
        // Every 32-byte input → 35-byte payload → 35 * 8 = 280 bits →
        // 56 base32 chars. Sanity check across a few inputs.
        val pubkeys = listOf(
            ByteArray(32) { 0x00 },
            ByteArray(32) { 0xFF.toByte() },
            ByteArray(32) { (it * 7).toByte() },
            ByteArray(32) { (255 - it).toByte() },
        )
        for (pk in pubkeys) {
            val encoded = StellarStrKey.encodeAccountID(pk)
            assertEquals(56, encoded.length)
            assertEquals('G', encoded[0])
        }
    }

    @Test
    fun rejects_non_32_byte_input() {
        assertThrows(IllegalArgumentException::class.java) {
            StellarStrKey.encodeAccountID(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StellarStrKey.encodeAccountID(ByteArray(33))
        }
    }
}
