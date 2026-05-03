package chat.onym.android.group

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import chat.onym.android.identity.Base64ByteArraySerializer
import chat.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Production [IntroKeyStore] backed by EncryptedSharedPreferences.
 * Whole-blob persistence — every mutation rewrites a single
 * preference key. Intro privkeys are tiny (32B each) and the
 * realistic count is dozens, not thousands, so the rewrite cost
 * is negligible and we get atomicity for free.
 *
 * The master key is the same `AES256_GCM` Android Keystore key
 * `IdentitySecretStore` uses. Backup-extraction yields ciphertext
 * the restoring device can't decrypt — see the backup_rules /
 * data_extraction_rules XML.
 */
class EncryptedPrefsIntroKeyStore(
    private val context: Context,
    private val prefsFileName: String = DEFAULT_PREFS_FILE_NAME,
) : IntroKeyStore {

    private val mutex = Mutex()

    private val _entriesFlow = MutableStateFlow<List<IntroKeyEntry>>(emptyList())
    override val entriesFlow: StateFlow<List<IntroKeyEntry>> = _entriesFlow.asStateFlow()

    init {
        // Seed the flow at construction time so the first subscriber
        // sees the on-disk state without needing an explicit reload.
        // EncryptedSharedPreferences read is lazy via the `prefs`
        // delegate, so this only does work if a previous session
        // wrote something.
        _entriesFlow.value = loadAllUnlocked().map { it.toEntry() }
    }

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

    override suspend fun save(entry: IntroKeyEntry) = mutex.withLock {
        val current = loadAllUnlocked().toMutableList()
        val existingIdx = current.indexOfFirst {
            it.introPub.contentEquals(entry.introPublicKey)
        }
        val stored = StoredIntroKey(
            introPub = entry.introPublicKey,
            introPriv = entry.introPrivateKey,
            ownerIdentityId = entry.ownerIdentityId.value,
            groupId = entry.groupId,
            createdAtMillis = entry.createdAtMillis,
        )
        if (existingIdx >= 0) current[existingIdx] = stored
        else current += stored
        saveAllUnlocked(current)
    }

    override suspend fun find(introPublicKey: ByteArray): IntroKeyEntry? = mutex.withLock {
        loadAllUnlocked()
            .firstOrNull { it.introPub.contentEquals(introPublicKey) }
            ?.toEntry()
    }

    override suspend fun listForOwner(ownerIdentityId: IdentityId): List<IntroKeyEntry> = mutex.withLock {
        loadAllUnlocked()
            .filter { it.ownerIdentityId == ownerIdentityId.value }
            .sortedByDescending { it.createdAtMillis }
            .map { it.toEntry() }
    }

    override suspend fun revoke(introPublicKey: ByteArray) = mutex.withLock {
        val current = loadAllUnlocked()
        val filtered = current.filterNot { it.introPub.contentEquals(introPublicKey) }
        if (filtered.size != current.size) saveAllUnlocked(filtered)
    }

    override suspend fun deleteForOwner(ownerIdentityId: IdentityId): Int = mutex.withLock {
        val current = loadAllUnlocked()
        val filtered = current.filterNot { it.ownerIdentityId == ownerIdentityId.value }
        val removed = current.size - filtered.size
        if (removed > 0) saveAllUnlocked(filtered)
        removed
    }

    // ─── private ──────────────────────────────────────────────────

    private fun loadAllUnlocked(): List<StoredIntroKey> {
        val raw = prefs.getString(KEY_BLOB, null) ?: return emptyList()
        return try {
            json.decodeFromString(StoredIntroKeysBlob.serializer(), raw).entries
        } catch (_: SerializationException) {
            // Corrupted blob → discard rather than crash. Acceptable
            // because this store holds ephemeral per-invite keys; if
            // we lose them, the worst that happens is in-flight
            // invites fail to deliver and the inviter re-shares.
            emptyList()
        }
    }

    private fun saveAllUnlocked(entries: List<StoredIntroKey>) {
        val raw = json.encodeToString(
            StoredIntroKeysBlob.serializer(),
            StoredIntroKeysBlob(entries),
        )
        // Use commit() rather than apply() — the call sites are
        // already inside `withContext(ioDispatcher)` (well, will be
        // once IntroIntroducer wraps them) and we want
        // write-confirmation before returning.
        prefs.edit().putString(KEY_BLOB, raw).commit()
        // Keep the reactive surface in lockstep with the on-disk
        // state. Inside the mutex on every mutation path, so no
        // emission interleaves with a partial in-flight write.
        _entriesFlow.value = entries.map { it.toEntry() }
    }

    private fun StoredIntroKey.toEntry(): IntroKeyEntry = IntroKeyEntry(
        introPublicKey = introPub,
        introPrivateKey = introPriv,
        ownerIdentityId = IdentityId(ownerIdentityId),
        groupId = groupId,
        createdAtMillis = createdAtMillis,
    )

    companion object {
        const val DEFAULT_PREFS_FILE_NAME = "chat.onym.android.intro_keys"
        private const val KEY_BLOB = "blob"
    }
}

/** On-disk envelope. Wraps the list so future fields (sort order,
 *  schema version, etc.) can be added without re-shaping the JSON. */
@Serializable
internal data class StoredIntroKeysBlob(val entries: List<StoredIntroKey>)

@Serializable
internal data class StoredIntroKey(
    @Serializable(with = Base64ByteArraySerializer::class) val introPub: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class) val introPriv: ByteArray,
    val ownerIdentityId: String,
    @Serializable(with = Base64ByteArraySerializer::class) val groupId: ByteArray,
    val createdAtMillis: Long,
)
