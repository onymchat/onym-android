package app.onym.android.foundation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.Base64

/**
 * Persists purchased [SeatEntitlement] credentials — one live
 * credential per `(audience, offerId)` pair, replacing any prior
 * entry for that pair on [put] but otherwise keeping every other
 * pair's entry untouched.
 *
 * Each pair lives under its OWN DataStore key rather than one shared
 * JSON array. That's load-bearing, not just tidiness: a single shared
 * blob means one undecodable entry (a future field, a corrupt write)
 * fails the WHOLE list's parse, and the previous implementation's
 * `getOrDefault(emptyList())` on that failure meant the very next
 * [put] would persist just the new entry and silently discard every
 * other previously-purchased entitlement — exactly the loss this
 * class's contract promises to avoid. Per-key storage makes that
 * failure mode structurally impossible: [put] only ever writes its
 * own key, and a decode failure on one entry in [all] only drops that
 * one entry.
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

    override suspend fun get(audience: String, offerId: String): SeatEntitlement? {
        val raw = snapshot()[keyFor(audience, offerId)] ?: return null
        return decodeOrNull(raw)
    }

    override suspend fun put(entitlement: SeatEntitlement) {
        dataStore.edit { preferences ->
            preferences[keyFor(entitlement.audience, entitlement.offerId)] =
                Base64.getEncoder().encodeToString(entitlement.rawBytes)
        }
    }

    override suspend fun all(): List<SeatEntitlement> =
        snapshot().asMap().entries
            .filter { (key, _) -> key.name.startsWith(KEY_PREFIX) }
            .mapNotNull { (_, value) -> decodeOrNull(value as? String ?: return@mapNotNull null) }

    private suspend fun snapshot(): Preferences =
        dataStore.data
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .first()

    private fun decodeOrNull(base64: String): SeatEntitlement? = runCatching {
        SeatEntitlement.decode(Base64.getDecoder().decode(base64))
    }.getOrNull()

    private fun keyFor(audience: String, offerId: String) =
        stringPreferencesKey("$KEY_PREFIX${audience}|$offerId")

    private companion object {
        const val KEY_PREFIX = "seat_entitlement."
    }
}
