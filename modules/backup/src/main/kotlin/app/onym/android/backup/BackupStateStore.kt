package app.onym.android.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** A snapshot whose outcome hasn't been confirmed — written to disk
 *  BEFORE the upload attempt, so a lost response reconciles to
 *  `unknown` rather than being silently treated as clean. */
@Serializable
data class PendingOperation(
    val componentId: String,
    val operationId: String,
    val snapshotDigest: String,
    val sealedByteSize: Long,
    val sealedFilePath: String,
    val acceptedTermsId: String,
    val sealedAtEpochSeconds: Long,
)

/** A composed-and-sealed snapshot that hit `payment_required`. Kept —
 *  never re-composed — so a retry after entitlement resubmits the
 *  identical operation id and bytes (re-composing would mint a new
 *  `snapshotSalt` and defeat both the retry and `already_retained`). */
@Serializable
data class PendingPayment(
    val componentId: String,
    val operationId: String,
    val snapshotDigest: String,
    val sealedByteSize: Long,
    val sealedFilePath: String,
    val acceptedTermsId: String,
    val offerIds: List<String>,
)

@Serializable
data class PersistedBackupState(
    val componentId: String? = null,
    val acceptedTermsId: String? = null,
    val pendingOperation: PendingOperation? = null,
    val pendingPayment: PendingPayment? = null,
    val lastSuccessAtEpochSeconds: Long? = null,
)

interface BackupStateStore {
    suspend fun load(): PersistedBackupState?
    suspend fun save(state: PersistedBackupState?)
}

/**
 * One-domain-one-blob DataStore persistence, matching `:moderation`'s
 * `DataStorePreferencesGateStateStore` convention — but keyed by
 * [componentId], not a single fixed key. A holder may back up to
 * several vendors at once (each with its own seed-derived key
 * material, per `BackupKeys`); this store gives each vendor its own
 * independent slice of the same DataStore file rather than sharing
 * one state blob across all of them. Backup state (terms pin, pending
 * operations) is not secret; the sealed bytes it points at live
 * elsewhere on disk.
 */
class DataStorePreferencesBackupStateStore(
    private val dataStore: DataStore<Preferences>,
    componentId: String,
) : BackupStateStore {
    private val key = stringPreferencesKey("backup_state.$componentId")

    override suspend fun load(): PersistedBackupState? {
        val raw = dataStore.data
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .first()[key] ?: return null
        return runCatching {
            Json.decodeFromString(PersistedBackupState.serializer(), raw)
        }.getOrNull()
    }

    override suspend fun save(state: PersistedBackupState?) {
        dataStore.edit { preferences ->
            if (state == null) {
                preferences.remove(key)
            } else {
                preferences[key] = Json.encodeToString(PersistedBackupState.serializer(), state)
            }
        }
    }
}
