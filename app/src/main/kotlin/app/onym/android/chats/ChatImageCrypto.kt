package app.onym.android.chats

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM seal/open for chat image blobs with a per-image random key.
 * The uploaded blob is `nonce ‖ ciphertext ‖ tag`, byte-compatible with
 * iOS `AES.GCM.SealedBox.combined`, so an image sealed on one platform
 * decrypts on the other. Blossom addresses blobs by the SHA-256 of
 * these stored bytes, re-checked before decrypting.
 *
 * Android twin of iOS `ChatImageCrypto`.
 */
object ChatImageCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    data class Sealed(val key: ByteArray, val blob: ByteArray, val sha256Hex: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sealed) return false
            return key.contentEquals(other.key) &&
                blob.contentEquals(other.blob) &&
                sha256Hex == other.sha256Hex
        }
        override fun hashCode(): Int =
            31 * (31 * key.contentHashCode() + blob.contentHashCode()) + sha256Hex.hashCode()
    }

    class HashMismatch : Exception("blob SHA-256 does not match the attachment")

    /** Encrypt [plaintext] under a fresh random 256-bit key. */
    fun seal(plaintext: ByteArray): Sealed {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertextAndTag = cipher.doFinal(plaintext)
        val blob = nonce + ciphertextAndTag
        return Sealed(key, blob, sha256Hex(blob))
    }

    /** Verify [blob]'s hash, then decrypt with [key]. */
    fun open(blob: ByteArray, key: ByteArray, expectedSha256Hex: String): ByteArray {
        if (!sha256Hex(blob).equals(expectedSha256Hex, ignoreCase = true)) throw HashMismatch()
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val ciphertextAndTag = blob.copyOfRange(NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertextAndTag)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
