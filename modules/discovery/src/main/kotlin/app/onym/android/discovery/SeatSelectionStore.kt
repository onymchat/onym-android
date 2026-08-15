package app.onym.android.discovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One module selection: the user (via the consent flow) chose
 * [componentId] to fill [seatType]. [manifestHash] pins the exact
 * destination-manifest bytes that were reviewed at selection time —
 * consent binds to reviewed bytes, never to a URL.
 */
@Serializable
data class ModuleSelection(
    /** Seat being filled, e.g. `"notary"` / `"transport.message"`. */
    val seatType: String,
    /** `onym:component:` id of the selected instance. */
    val componentId: String,
    /** `onym:component:` id of the Discovery provider the selection
     *  came through (attribution for the settings UI). */
    val providerId: String,
    /** `sha256:` digest of the reviewed destination manifest. */
    val manifestHash: String,
    /** Accepted offer, when the manifest carried one. */
    val offerId: String? = null,
    /** RFC 3339 selection timestamp (client clock). */
    val selectedAt: String,
)

/**
 * Append-only history of module selections. The **active** selection
 * per seat is the last appended entry for that `seatType` — switching
 * modules appends, it never rewrites, so the history stays a
 * faithful audit trail of what was consented to and when.
 */
interface SeatSelectionStore {
    /** Append one selection to the history. */
    suspend fun appendSelection(selection: ModuleSelection)

    /** Full history, oldest first. `[]` on a fresh install. */
    suspend fun loadSelections(): List<ModuleSelection>

    /** Active selection per seat: the last appended entry for each
     *  `seatType`. */
    suspend fun activeSelections(): Map<String, ModuleSelection>

    /** Test/utility — wipe the history. */
    suspend fun clear()
}

/**
 * [DataStore]-backed [SeatSelectionStore]. The whole history is one
 * JSON list under a fixed `discovery.` preference key — selections
 * are rare (a user action each), so read-modify-write of the full
 * list inside a single `edit { }` is both simple and atomic.
 */
class DataStorePreferencesSeatSelectionStore(
    private val dataStore: DataStore<Preferences>,
) : SeatSelectionStore {

    override suspend fun appendSelection(selection: ModuleSelection) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SELECTIONS]?.let(::decodeOrEmpty) ?: emptyList()
            prefs[KEY_SELECTIONS] = jsonFormat.encodeToString(
                SelectionsDocument.serializer(),
                SelectionsDocument(version = 1, selections = current + selection),
            )
        }
    }

    override suspend fun loadSelections(): List<ModuleSelection> {
        val raw = dataStore.data.first()[KEY_SELECTIONS] ?: return emptyList()
        return decodeOrEmpty(raw)
    }

    override suspend fun activeSelections(): Map<String, ModuleSelection> =
        loadSelections().associateBy { it.seatType }
    // associateBy keeps the *last* value per key — exactly the
    // "active = last appended per seatType" rule.

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_SELECTIONS) }
    }

    private fun decodeOrEmpty(raw: String): List<ModuleSelection> = try {
        jsonFormat.decodeFromString(SelectionsDocument.serializer(), raw).selections
    } catch (_: SerializationException) {
        emptyList()
    }

    /** Versioned on-disk envelope, mirroring :chain's
     *  `KnownRelayersDocument` convention. */
    @Serializable
    private data class SelectionsDocument(
        val version: Int,
        val selections: List<ModuleSelection>,
    )

    private companion object {
        private val KEY_SELECTIONS = stringPreferencesKey("discovery.seat_selections")
        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
