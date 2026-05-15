package app.onym.android.identity

/**
 * Stellar StrKey encoding (SEP-0023).
 *
 * Encodes Ed25519 public keys as G… account IDs via the canonical
 * Stellar StrKey format: version byte || payload || CRC16-XMODEM
 * checksum, then base32 (RFC 4648, no padding).
 *
 * Ported 1:1 from `onym-ios/Sources/OnymIOS/Identity/StellarStrKey.swift`
 * — the algorithm is fixed and unchanging, so a one-file port avoids
 * pulling in a full Stellar SDK. Kotlin doesn't ship base32 in its
 * standard library, hence the hand-rolled encoder below.
 */
object StellarStrKey {

    /** Version byte for Ed25519 public key (account ID): `6 << 3 = 48`. */
    private const val VERSION_ACCOUNT_ID: Byte = (6 shl 3).toByte()

    /** Encode a 32-byte Ed25519 public key as a Stellar account ID (G…). */
    fun encodeAccountID(publicKey: ByteArray): String {
        require(publicKey.size == 32) {
            "Ed25519 public key must be 32 bytes, got ${publicKey.size}"
        }
        val payload = ByteArray(1 + publicKey.size)
        payload[0] = VERSION_ACCOUNT_ID
        System.arraycopy(publicKey, 0, payload, 1, publicKey.size)
        val crc = crc16XModem(payload)
        val withChecksum = ByteArray(payload.size + 2)
        System.arraycopy(payload, 0, withChecksum, 0, payload.size)
        // Little-endian (Stellar convention; differs from CRC16's natural BE).
        withChecksum[payload.size]     = (crc and 0xFF).toByte()
        withChecksum[payload.size + 1] = ((crc shr 8) and 0xFF).toByte()
        return base32Encode(withChecksum)
    }

    // ─── CRC16-XMODEM ────────────────────────────────────────────────

    private fun crc16XModem(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
        }
        return crc and 0xFFFF
    }

    // ─── Base32 (RFC 4648, no padding) ──────────────────────────────

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0L
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = ((buffer shr bitsLeft) and 0x1F).toInt()
                out.append(BASE32_ALPHABET[index])
            }
        }
        if (bitsLeft > 0) {
            val index = ((buffer shl (5 - bitsLeft)) and 0x1F).toInt()
            out.append(BASE32_ALPHABET[index])
        }
        return out.toString()
    }
}
