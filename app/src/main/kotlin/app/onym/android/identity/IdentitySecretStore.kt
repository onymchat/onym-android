package app.onym.android.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Atomic, per-identity persistence for the on-device identity secrets.
 *
 * One [EncryptedSharedPreferences] file (default name
 * `app.onym.android.identity`) holds the JSON-encoded
 * [StoredSnapshot] for each identity, keyed under
 * `snapshot.<identityId>`. A separate index key `index` holds the
 * ordered list of identity ids — the source of truth for "which
 * identities exist on this device". A `current` key holds the
 * currently-selected [IdentityId] (or is absent when no identity is
 * selected).
 *
 * Every mutation is one `editor.put*(...).commit()` per logical edit
 * — no intermediate state where one secret has been written and
 * another has not, so partial-failure cleanup is unnecessary.
 *
 * The master key is `AES256_GCM` from the Android Keystore; the JCA
 * key handle is non-exportable (hardware-backed on devices with a
 * secure element, software-only on emulators). The encrypted blob is
 * useless to anyone without the matching Keystore handle, so a backup
 * extraction (with `allowBackup="false"` defeated) yields ciphertext
 * the restoring device cannot decrypt — see also
 * `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`.
 *
 * The [prefsFileName] is configurable so instrumented tests can
 * isolate each test case in a fresh prefs file (per-test UUID name)
 * and wipe cleanly without colliding with the production identity item.
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ─── identity index ────────────────────────────────────────────

    /** Ordered list of identity ids on this device. Order = creation
     *  order; UI may surface a different sort. Returns an empty list
     *  when no identities exist. */
    fun listIds(): List<IdentityId> {
        val raw = try {
            prefs.getString(KEY_INDEX, null)
        } catch (t: Throwable) {
            throw IdentityError.StorageRead(t)
        } ?: return emptyList()
        return try {
            json.decodeFromString(StoredIndex.serializer(), raw).ids.map(::IdentityId)
        } catch (e: SerializationException) {
            throw IdentityError.StoredSnapshotInvalid("index decode failed: ${e.message}")
        }
    }

    /** Identity ids that have a snapshot persisted under them. */
    fun listSnapshots(): List<Pair<IdentityId, StoredSnapshot>> =
        listIds().mapNotNull { id -> load(id)?.let { id to it } }

    // ─── current selection ────────────────────────────────────────

    /** The currently-selected identity, or `null` if none is selected
     *  (cold install OR last-active identity was removed). */
    fun loadCurrent(): IdentityId? {
        val raw = try {
            prefs.getString(KEY_CURRENT, null)
        } catch (t: Throwable) {
            throw IdentityError.StorageRead(t)
        } ?: return null
        return raw.takeIf { it.isNotBlank() }?.let(::IdentityId)
    }

    /** Persist the active identity selection. `null` clears it. */
    fun saveCurrent(id: IdentityId?) {
        val editor = prefs.edit()
        if (id == null) editor.remove(KEY_CURRENT)
        else editor.putString(KEY_CURRENT, id.value)
        if (!commit(editor)) throw IdentityError.StorageWrite(null)
    }

    // ─── per-identity snapshot ────────────────────────────────────

    /** Read the snapshot for [id], or `null` if the slot is empty. */
    fun load(id: IdentityId): StoredSnapshot? {
        val raw = try {
            prefs.getString(snapshotKey(id), null)
        } catch (t: Throwable) {
            throw IdentityError.StorageRead(t)
        } ?: return null
        return try {
            json.decodeFromString(StoredSnapshot.serializer(), raw)
        } catch (e: SerializationException) {
            throw IdentityError.StoredSnapshotInvalid("decode failed for $id: ${e.message}")
        }
    }

    /** Persist a snapshot for [id]. Atomically updates the index to
     *  include the id if it wasn't there yet, and writes both keys in
     *  the same `commit()` so a process crash mid-edit doesn't orphan
     *  a snapshot or index entry. */
    fun save(id: IdentityId, snapshot: StoredSnapshot) {
        val raw = try {
            json.encodeToString(StoredSnapshot.serializer(), snapshot)
        } catch (e: SerializationException) {
            throw IdentityError.StoredSnapshotInvalid("encode failed for $id: ${e.message}")
        }

        // Add id to the index if absent (preserves insertion order).
        val ids = listIds()
        val newIds = if (id in ids) ids else ids + id
        val indexRaw = json.encodeToString(
            StoredIndex.serializer(),
            StoredIndex(newIds.map { it.value }),
        )

        val editor = prefs.edit()
            .putString(snapshotKey(id), raw)
            .putString(KEY_INDEX, indexRaw)
        if (!commit(editor)) throw IdentityError.StorageWrite(null)
    }

    /** Delete a single identity's snapshot + remove it from the index.
     *  No-op if [id] isn't in the index. If the removed id was the
     *  currently-selected one, the current selection is also cleared
     *  (caller is responsible for picking a replacement). */
    fun wipe(id: IdentityId) {
        val ids = listIds()
        if (id !in ids) return
        val newIds = ids - id
        val indexRaw = json.encodeToString(
            StoredIndex.serializer(),
            StoredIndex(newIds.map { it.value }),
        )
        val editor = prefs.edit()
            .remove(snapshotKey(id))
            .putString(KEY_INDEX, indexRaw)
        if (loadCurrent() == id) editor.remove(KEY_CURRENT)
        if (!commit(editor)) throw IdentityError.StorageDelete(null)
    }

    /** Delete every identity + the index + the current selection.
     *  Used by tests and the (future) "reset device" flow. */
    fun wipeAll() {
        val editor = prefs.edit().clear()
        if (!commit(editor)) throw IdentityError.StorageDelete(null)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun commit(editor: SharedPreferences.Editor): Boolean = try {
        editor.commit()
    } catch (t: Throwable) {
        throw IdentityError.StorageWrite(t)
    }

    private fun snapshotKey(id: IdentityId): String = "$KEY_SNAPSHOT_PREFIX${id.value}"

    companion object {
        const val DEFAULT_PREFS_FILE_NAME = "app.onym.android.identity"
        private const val KEY_INDEX = "index"
        private const val KEY_CURRENT = "current"
        private const val KEY_SNAPSHOT_PREFIX = "snapshot."
    }
}

/** On-disk envelope for the identity index — wraps the list so
 *  future fields (sort order, deleted-tombstones, etc.) can be added
 *  without flattening the JSON shape. */
@kotlinx.serialization.Serializable
internal data class StoredIndex(val ids: List<String>)
