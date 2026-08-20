package app.onym.android.foundation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64

/** One stored entitlement's raw bytes, keyed by (audience, offerId)
 *  for lookup. */
@Serializable
private data class StoredSeatEntitlement(
    val audience: String,
    val offerId: String,
    val rawBytesBase64: String,
)

/**
 * Persists purchased [SeatEntitlement] credentials — one live
 * credential per `(audience, offerId)` pair, replacing any prior
 * entry for that pair on [put] but otherwise keeping undecodable or
 * foreign entries untouched (a store that discards what it can't
 * currently parse would silently drop a real purchase across an
 * app-version skew).
 *
 * Mirrors `SeatEntitlementStore` (`FileSeatEntitlementStore` in the
 * app target) in onym-ios OnymBilling. A DataStore-backed
 * implementation is used here rather than a raw file store, matching
 * this codebase's convention elsewhere (`:moderation`,
 * `PinnedConsentStore`) — no directory-creation failure to fall back
 * from, and therefore no weaker-protected fallback location to avoid.
 */
interface SeatEntitlementStore {
    suspend fun get(audience: String, offerId: String): SeatEntitlement?
    suspend fun put(entitlement: SeatEntitlement)
    suspend fun all(): List<SeatEntitlement>
}

class DataStorePreferencesSeatEntitlementStore(
    private val dataStore: DataStore<Preferences>,
) : SeatEntitlementStore {

    override suspend fun get(audience: String, offerId: String): SeatEntitlement? =
        loadAll().firstOrNull { it.audience == audience && it.offerId == offerId }
            ?.let { decodeOrNull(it) }

    override suspend fun put(entitlement: SeatEntitlement) {
        val existing = loadRaw()
        val filtered = existing.filterNot { it.audience == entitlement.audience && it.offerId == entitlement.offerId }
        val updated = filtered + StoredSeatEntitlement(
            audience = entitlement.audience,
            offerId = entitlement.offerId,
            rawBytesBase64 = Base64.getEncoder().encodeToString(entitlement.rawBytes),
        )
        save(updated)
    }

    override suspend fun all(): List<SeatEntitlement> = loadRaw().mapNotNull(::decodeOrNull)

    private fun decodeOrNull(stored: StoredSeatEntitlement): SeatEntitlement? = runCatching {
        SeatEntitlement.decode(Base64.getDecoder().decode(stored.rawBytesBase64))
    }.getOrNull()

    private suspend fun loadAll(): List<StoredSeatEntitlement> = loadRaw()

    private suspend fun loadRaw(): List<StoredSeatEntitlement> {
        val raw = dataStore.data
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .first()[KEY] ?: return emptyList()
        return runCatching {
            Json.decodeFromString(ListSerializer(StoredSeatEntitlement.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun save(entries: List<StoredSeatEntitlement>) {
        dataStore.edit { preferences ->
            preferences[KEY] = Json.encodeToString(ListSerializer(StoredSeatEntitlement.serializer()), entries)
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("seat_entitlements")
    }
}
