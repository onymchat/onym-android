package app.onym.android.persistence

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Field-level AES-256-GCM encryption for Room columns. The on-disk
 * layout is `nonce(12) || ciphertext || tag(16)` — GCM appends the
 * tag to the ciphertext, so a single contiguous `ByteArray` is
 * enough to round-trip.
 *
 * Defence-in-depth wrapper for already-encrypted payloads: the
 * inbox transport delivers NaCl-sealed bytes from the sender, which
 * we wrap in this AES layer so disk forensics with the device
 * locked can't read the inner ciphertext structure either. The
 * inner ciphertext is recoverable only by the recipient's X25519
 * private key, which lives in `IdentityRepository`.
 *
 * Ported from `stellar-mls/clients/android/StellarChat/app/src/main/java/app/onym/android/crypto/StorageEncryption.kt`,
 * minus the NSE / shared-keystore bits that don't apply to onym-android.
 *
 * **Ctor takes the AES key directly** rather than the singleton
 * `init(context: Context)` pattern — tests pass a fresh key per case
 * (no Robolectric Keystore dance), and production wires the
 * Keystore-backed loader through [fromContext] at app startup.
 *
 * Mirrors `StorageEncryption.swift` from onym-ios PR #16.
 */
class StorageEncryption internal constructor(private val keySpec: SecretKeySpec) {

    /** Encrypt bytes. Returns `nonce(12) || ciphertext || tag(16)`. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv  // GCM ctor populates a fresh 12-byte nonce
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return iv + ciphertextWithTag
    }

    /** Encrypt a UTF-8 string; same on-disk layout as [encrypt]. */
    fun encrypt(string: String): ByteArray = encrypt(string.toByteArray(Charsets.UTF_8))

    /** Decrypt the combined `nonce(12) || ciphertext || tag(16)` layout. */
    fun decrypt(combined: ByteArray): ByteArray {
        require(combined.size >= NONCE_SIZE + TAG_SIZE) {
            "ciphertext too short: ${combined.size} bytes (need >= ${NONCE_SIZE + TAG_SIZE})"
        }
        val iv = combined.copyOfRange(0, NONCE_SIZE)
        val ciphertextWithTag = combined.copyOfRange(NONCE_SIZE, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE * 8, iv))
        return cipher.doFinal(ciphertextWithTag)
    }

    /** Decrypt and decode the result as UTF-8. */
    fun decryptString(combined: ByteArray): String =
        String(decrypt(combined), Charsets.UTF_8)

    companion object {
        /** AES-GCM nonce size in bytes. */
        const val NONCE_SIZE = 12

        /** AES-GCM auth tag size in bytes (128 bits). */
        const val TAG_SIZE = 16

        /** Current key-derivation version. Increment + handle migration
         *  if the [hkdfSha256] inputs change. */
        private const val DERIVATION_VERSION = 1

        private const val PREFS_NAME = "app.onym.android.storage_root"
        private const val ROOT_KEY_PREF = "root_secret"

        /**
         * Production loader: pulls the 32-byte root secret from
         * EncryptedSharedPreferences (creating one on first launch),
         * then HKDF-derives the AES key. The root never leaves the
         * Keystore-backed prefs file; the storage key is derived
         * fresh on every load so future versions can rotate keys
         * by bumping [DERIVATION_VERSION].
         *
         * Synchronisation isn't needed here — every caller gets its
         * own [StorageEncryption] instance with the same derived
         * key bytes. Wire one instance into the persistence root in
         * `OnymApplication` and pass it to every `Room*Store`.
         */
        fun fromContext(context: Context): StorageEncryption {
            val rootSecret = loadOrCreateRootSecret(context)
            val storageKey = hkdfSha256(
                ikm = rootSecret,
                salt = "app.onym.android.storage".toByteArray(Charsets.UTF_8),
                info = "local-storage-v$DERIVATION_VERSION".toByteArray(Charsets.UTF_8),
                length = 32,
            )
            return StorageEncryption(SecretKeySpec(storageKey, "AES"))
        }

        private fun loadOrCreateRootSecret(context: Context): ByteArray {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val storedHex = prefs.getString(ROOT_KEY_PREF, null)
            if (storedHex != null) return storedHex.hexToBytes()
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(ROOT_KEY_PREF, secret.toHex()).apply()
            return secret
        }

        /**
         * RFC-5869 HKDF-SHA256. Same algorithm as the Bip39 derivation
         * chain in `app.onym.android.identity` but lives here so the
         * persistence layer doesn't import from identity (touch-surface
         * rule: persistence seam talks to its backend + nothing above).
         */
        private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length in 1..(255 * 32)) { "HKDF output length out of range: $length" }
            val mac = Mac.getInstance("HmacSHA256")
            // extract
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            // expand
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter: Byte = 1
            while (pos < length) {
                mac.reset()
                mac.update(t)
                mac.update(info)
                mac.update(counter)
                t = mac.doFinal()
                val take = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, take)
                pos += take
                counter = (counter + 1).toByte()
            }
            return out
        }

        private fun ByteArray.toHex(): String = buildString(size * 2) {
            for (b in this@toHex) append("%02x".format(b.toInt() and 0xFF))
        }

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0) { "hex string must have even length" }
            return ByteArray(length / 2) { i ->
                ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
            }
        }
    }
}
