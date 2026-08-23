package app.onym.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.onym.android.push.PushSigner
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Signs push registration sessions with a DEVICE-LOCAL random Ed25519
 * key — deliberately NOT the identity key.
 *
 * Why not the identity key (the iOS reviewer's catch, and it applies
 * identically here): the backend verifies the registration signature
 * and then DISCARDS the user key — any key that verifies works, so
 * the identity key buys nothing. What it COSTS is linkage: the
 * replace-all registration refreshes periodically, and signing each
 * refresh with whichever identity key is current would hand the
 * backend a stable signer identity to join persona keys across
 * refreshes. A random per-device key carries exactly the information
 * the registration already discloses — "these tags belong to one
 * device" — and nothing more; the Settings footnote names it.
 *
 * The keypair is generated from [SecureRandom] on first use and the
 * 32-byte seed persists in its own [EncryptedSharedPreferences] file
 * (the house pattern for locally-generated secrets —
 * `EncryptedPrefsIntroKeyStore` / `IdentitySecretStore`: Android
 * Keystore AES256_GCM master key, so a backup extraction yields
 * ciphertext the restoring device cannot decrypt). The key never
 * leaves this class; only detached signatures and the public half's
 * `onym:key:<hex>` reference do. Losing the file (reinstall) is
 * harmless: the next pass registers afresh under a new key, and
 * unregister-by-signature only ever targets tokens this same install
 * registered.
 */
class DevicePushSigner(
    private val context: Context,
    private val prefsFileName: String = DEFAULT_PREFS_FILE_NAME,
) : PushSigner {

    private val mutex = Mutex()
    private var cached: Ed25519PrivateKeyParameters? = null

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            prefsFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun userKeyId(): String =
        "onym:key:" + signingKey().generatePublicKey().encoded.joinToString("") {
            "%02x".format(it)
        }

    override suspend fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer().apply {
            init(true, signingKey())
            update(message, 0, message.size)
        }
        return signer.generateSignature()
    }

    /** Load-or-mint, single-flight, off the calling thread (the
     * EncryptedSharedPreferences open and the write both touch
     * disk + Keystore). */
    private suspend fun signingKey(): Ed25519PrivateKeyParameters = mutex.withLock {
        cached?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val stored = prefs.getString(KEY_SEED, null)
            val keyBytes = if (stored != null) {
                Base64.getDecoder().decode(stored)
            } else {
                ByteArray(SEED_BYTES).also {
                    SecureRandom().nextBytes(it)
                    // commit(), not apply(): the key must be durable
                    // before anything is signed with it.
                    prefs.edit()
                        .putString(KEY_SEED, Base64.getEncoder().encodeToString(it))
                        .commit()
                }
            }
            Ed25519PrivateKeyParameters(keyBytes, 0).also { cached = it }
        }
    }

    companion object {
        /** Own file, one-domain-one-blob convention. */
        const val DEFAULT_PREFS_FILE_NAME = "app.onym.android.push_signer"
        private const val KEY_SEED = "push.signer.ed25519Seed"
        private const val SEED_BYTES = 32
    }
}
