package chat.onym.android.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Atomic, single-blob persistence for the on-device identity secrets.
 *
 * One [EncryptedSharedPreferences] file (default name
 * `chat.onym.android.identity`) holds the JSON-encoded [StoredSnapshot]
 * under a single preference key. Every mutation is one
 * `editor.putString(...).commit()` — no intermediate state where one
 * secret has been written and another has not, so partial-failure
 * cleanup is unnecessary.
 *
 * The master key is `AES256_GCM` from the Android Keystore — the JCA
 * key handle is non-exportable (hardware-backed on devices with a
 * secure element, software-only on emulators). The encrypted blob is
 * useless to anyone without the matching Keystore handle, so a backup
 * extraction (with `allowBackup="false"` defeated) yields ciphertext
 * the restoring device cannot decrypt — see also
 * `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`.
 *
 * The [prefsFileName] is configurable so instrumented tests can isolate
 * each test case in a fresh prefs file (per-test UUID name) and wipe
 * cleanly without colliding with the production identity item.
 *
 * @param context any Android Context (Application context preferred to
 *        avoid leaks).
 * @param prefsFileName name of the EncryptedSharedPreferences file.
 *        DON'T change this in production — changing it orphans the
 *        previous identity. Tests use unique names for isolation.
 */
class IdentitySecretStore(
    private val context: Context,
    private val prefsFileName: String = DEFAULT_PREFS_FILE_NAME,
) {
    /**
     * EncryptedSharedPreferences is initialized lazily so construction
     * is cheap and any Keystore failures surface at first use (where
     * we can wrap them in [IdentityError.StorageRead]) rather than at
     * `IdentityRepository` construction time.
     */
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

    /** JSON encoder — strict by default; tolerates unknown keys on
     *  decode so a future StoredSnapshot field addition won't brick
     *  existing installs. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Read the persisted snapshot, or `null` if there's no saved identity. */
    fun load(): StoredSnapshot? {
        val raw = try {
            prefs.getString(KEY_SNAPSHOT, null)
        } catch (t: Throwable) {
            throw IdentityError.StorageRead(t)
        } ?: return null
        return try {
            json.decodeFromString(StoredSnapshot.serializer(), raw)
        } catch (e: SerializationException) {
            throw IdentityError.StoredSnapshotInvalid("decode failed: ${e.message}")
        }
    }

    /** Persist the snapshot. Overwrites any existing value atomically. */
    fun save(snapshot: StoredSnapshot) {
        val raw = try {
            json.encodeToString(StoredSnapshot.serializer(), snapshot)
        } catch (e: SerializationException) {
            throw IdentityError.StoredSnapshotInvalid("encode failed: ${e.message}")
        }
        val ok = try {
            prefs.edit().putString(KEY_SNAPSHOT, raw).commit()
        } catch (t: Throwable) {
            throw IdentityError.StorageWrite(t)
        }
        if (!ok) throw IdentityError.StorageWrite(null)
    }

    /** Delete the persisted snapshot. No-op if there's nothing stored. */
    fun wipe() {
        val ok = try {
            prefs.edit().remove(KEY_SNAPSHOT).commit()
        } catch (t: Throwable) {
            throw IdentityError.StorageDelete(t)
        }
        if (!ok) throw IdentityError.StorageDelete(null)
    }

    companion object {
        const val DEFAULT_PREFS_FILE_NAME = "chat.onym.android.identity"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
