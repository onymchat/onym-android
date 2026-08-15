package app.onym.android.foundation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Persistence seam for pinned consent records. DataStore-backed — a
 * consent record is a local pin of public manifest bytes, not a
 * secret. Same load/save shape as `PinnedConsentStore` in onym-ios
 * OnymFoundation and `RelayerSelectionStore` in `:chain`.
 */
interface PinnedConsentStore {
    /** All records, oldest first. `[]` on a fresh install or an
     *  unreadable blob. */
    suspend fun load(): List<PinnedConsentRecord>

    /** Persist the full record list. */
    suspend fun save(records: List<PinnedConsentRecord>)

    /**
     * Mint and persist consent to a reviewed manifest.
     *
     * Takes a [ReviewedServiceManifest] — not raw bytes — so only
     * reviewer-verified bytes can be pinned. Accepting a record for a
     * componentId that already has one deactivates the previous
     * record and keeps it as history (consent artifacts are
     * immutable, switching is a fresh record).
     */
    suspend fun accept(
        reviewed: ReviewedServiceManifest,
        sourceLabel: String? = null,
        offerId: String? = null,
        acceptedAt: Instant = Instant.now(),
    ): PinnedConsentRecord {
        val componentId = reviewed.signedManifest.componentId
        val records = load().map { record ->
            if (record.componentId == componentId) record.copy(isActive = false) else record
        }
        val record = PinnedConsentRecord.from(
            reviewed = reviewed,
            sourceLabel = sourceLabel,
            offerId = offerId,
            acceptedAt = acceptedAt,
            isActive = true,
        )
        save(records + record)
        return record
    }

    /** The active consent for a component, if any. */
    suspend fun activeRecord(componentId: String): PinnedConsentRecord? =
        load().lastOrNull { it.componentId == componentId && it.isActive }
}

/**
 * [DataStore]-backed [PinnedConsentStore]. Stores the record list as
 * a JSON string under a fixed preference key in the consent DataStore
 * file the app wiring owns (a new file, not shared with the relayer /
 * discovery stores).
 *
 * Threading: DataStore serialises writes internally; safe to call
 * from multiple coroutines without external sync.
 */
class DataStorePreferencesPinnedConsentStore(
    private val dataStore: DataStore<Preferences>,
) : PinnedConsentStore {

    override suspend fun load(): List<PinnedConsentRecord> {
        val raw = dataStore.data.first()[KEY_RECORDS] ?: return emptyList()
        return try {
            jsonFormat.decodeFromString(recordsSerializer, raw)
        } catch (_: Exception) {
            // Corrupt blob (bad JSON — SerializationException; bad
            // base64 / timestamp inside a field serializer) → empty.
            // Consent is re-obtainable through the normal review
            // flow; never crash on a bad store.
            emptyList()
        }
    }

    override suspend fun save(records: List<PinnedConsentRecord>) {
        dataStore.edit { prefs ->
            prefs[KEY_RECORDS] = jsonFormat.encodeToString(recordsSerializer, records)
        }
    }

    private companion object {
        val KEY_RECORDS = stringPreferencesKey("consent.records")
        val recordsSerializer = ListSerializer(PinnedConsentRecord.serializer())

        /** Tolerant of fields a future release may add to the record
         *  schema — old builds must not drop a user's consent history
         *  on downgrade. */
        val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
