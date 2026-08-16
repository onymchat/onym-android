package app.onym.android.moderation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

// ─── Gate state ──────────────────────────────────────────────────────

/** Last successful gate answer + when it landed. Persisted so a
 * relaunch inside the grace window serves the last known state instead
 * of blocking on the network. */
@Serializable
data class PersistedGateState(
    val lastResult: GateCheckResult,
    /** RFC 3339 UTC. */
    val lastSuccessAt: String,
    /**
     * A refusal the backend answered after [lastSuccessAt] — STICKY:
     * only a later successful check clears it. Without persistence a
     * refusal survived only in memory, so refuse → force-stop →
     * airplane mode → relaunch derived `Unreachable` against the
     * cached `Clear` and served the app for the whole grace window —
     * a reachable backend's "no" laundered into a network condition.
     */
    val refusalReason: CheckRequiredReason? = null,
    /** RFC 3339 UTC; when [refusalReason] landed. */
    val refusedAt: String? = null,
)

interface GateStateStore {
    suspend fun load(): PersistedGateState?
    suspend fun save(state: PersistedGateState?)
}

/** One-domain-one-blob DataStore persistence, the repo's convention.
 * Gate state is not secret; the marks live at Google and the verdicts
 * at the backend. */
class DataStorePreferencesGateStateStore(
    private val dataStore: DataStore<Preferences>,
) : GateStateStore {
    override suspend fun load(): PersistedGateState? {
        val raw = dataStore.data.first()[KEY] ?: return null
        return runCatching {
            ModerationJson.json.decodeFromString(PersistedGateState.serializer(), raw)
        }.getOrNull()
    }

    override suspend fun save(state: PersistedGateState?) {
        dataStore.edit { preferences ->
            if (state == null) {
                preferences.remove(KEY)
            } else {
                preferences[KEY] =
                    ModerationJson.json.encodeToString(PersistedGateState.serializer(), state)
            }
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("moderation_gate_state")
    }
}

// ─── Mandate records ─────────────────────────────────────────────────

/**
 * A consented mandate with everything needed to keep displaying and
 * re-verifying the consent: the mandate itself, the exact manifest
 * bytes it pinned, and the flags the gate flow reads.
 */
@Serializable
data class MandateRecord(
    val mandate: ModerationMandate,
    /** The consented manifest's exact bytes, base64 (consent record,
     * not secret). */
    val manifestBytesBase64: String,
    val authorityName: String,
    val countersigned: Boolean,
    /**
     * Whether the finalized mandate reached the authority's
     * register-mandate endpoint. Deferred seam: registration is not
     * yet implemented on Android, so this stays false and re-consent /
     * registration retry logic hangs off it later.
     */
    val authorityRegistered: Boolean = false,
    val isActive: Boolean,
    val createdAt: String,
)

/**
 * The best human route this record can offer a holder whose marked
 * device the backend cannot resolve: the consented manifest's
 * new-holder procedure when it declares one, else the authority's
 * name and componentId. Derived from the retained exact bytes — the
 * gate's `checkRequired(reidentificationRequired)` answer carries no
 * routes of its own, and a back-blocked screen with no contact is the
 * silent brick the profile forbids (§5.2 item 3).
 */
fun MandateRecord.authorityContactLine(): String {
    val manifest = runCatching {
        ModerationJson.json.decodeFromString(
            AuthorityManifest.serializer(),
            java.util.Base64.getDecoder().decode(manifestBytesBase64).decodeToString(),
        )
    }.getOrNull()
    val newHolder = manifest?.newHolderAppeal?.takeIf { it.isNotBlank() }
    val identity = "$authorityName (${mandate.authority})"
    return if (newHolder != null) "$identity — $newHolder" else identity
}

interface MandateStore {
    suspend fun load(): List<MandateRecord>
    suspend fun save(records: List<MandateRecord>)
}

class DataStorePreferencesMandateStore(
    private val dataStore: DataStore<Preferences>,
) : MandateStore {
    override suspend fun load(): List<MandateRecord> {
        val raw = dataStore.data.first()[KEY] ?: return emptyList()
        return runCatching {
            ModerationJson.json.decodeFromString(ListSerializer(MandateRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    override suspend fun save(records: List<MandateRecord>) {
        dataStore.edit { preferences ->
            preferences[KEY] = ModerationJson.json.encodeToString(
                ListSerializer(MandateRecord.serializer()),
                records,
            )
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("moderation_mandates")
    }
}
