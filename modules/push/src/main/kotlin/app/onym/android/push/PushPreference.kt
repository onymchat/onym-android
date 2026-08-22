package app.onym.android.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The push opt-in (default OFF) plus the reconciler's durable
 * registration bookkeeping. The seam mirrors :chats-core's
 * `ReadReceiptsPreferenceProvider` trio (interface + DataStore impl +
 * in-memory test double), widened because the reconciler's contract
 * lives in what survives a process death:
 *
 * - [lastRegistrationFingerprint] / [lastRegisteredAt] /
 *   [registrationExpiresAt] — what the backend currently holds, so an
 *   unchanged, unexpired registration sends nothing.
 * - [lastRegisteredToken] — the token the backend holds, so disable
 *   can unregister even after FCM stops answering.
 * - [pendingUnregisterToken] — a "forget this token" debt, written
 *   BEFORE the unregister attempt and cleared only on success, so an
 *   offline disable (or a token rotation) is retried until the
 *   server has actually forgotten.
 */
interface PushPreferenceProvider {
    /** Reactive read for the Settings toggle. Default OFF. */
    val enabledFlow: Flow<Boolean>

    /** Whether the backend currently holds a confirmed registration
     * (fingerprint present) — reactive, so the Settings toggle can
     * distinguish "on" from "still activating". */
    val registeredFlow: Flow<Boolean>

    suspend fun enabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)

    /** Lowercase hex of `sha256(sha256(tokenBytes) || subscriptionsDigest)`
     * for the registration the backend currently holds; null when
     * unregistered. */
    suspend fun lastRegistrationFingerprint(): String?
    suspend fun lastRegisteredAt(): Instant?
    suspend fun registrationExpiresAt(): Instant?
    suspend fun lastRegisteredToken(): String?

    suspend fun recordRegistration(
        fingerprint: String,
        registeredAt: Instant,
        expiresAt: Instant?,
        token: String,
    )

    suspend fun clearRegistration()

    suspend fun pendingUnregisterToken(): String?
    suspend fun setPendingUnregisterToken(token: String?)
}

/** Production impl — DataStore Preferences. */
class DataStorePushPreferenceProvider(
    private val dataStore: DataStore<Preferences>,
) : PushPreferenceProvider {

    override val enabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ENABLED] ?: false
    }

    override val registeredFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FINGERPRINT] != null
    }

    override suspend fun enabled(): Boolean = dataStore.data.first()[ENABLED] ?: false

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[ENABLED] = enabled }
    }

    override suspend fun lastRegistrationFingerprint(): String? =
        dataStore.data.first()[FINGERPRINT]

    override suspend fun lastRegisteredAt(): Instant? =
        dataStore.data.first()[REGISTERED_AT]?.let(Instant::ofEpochSecond)

    override suspend fun registrationExpiresAt(): Instant? =
        dataStore.data.first()[EXPIRES_AT]?.let(Instant::ofEpochSecond)

    override suspend fun lastRegisteredToken(): String? =
        dataStore.data.first()[REGISTERED_TOKEN]

    override suspend fun recordRegistration(
        fingerprint: String,
        registeredAt: Instant,
        expiresAt: Instant?,
        token: String,
    ) {
        dataStore.edit { prefs ->
            prefs[FINGERPRINT] = fingerprint
            prefs[REGISTERED_AT] = registeredAt.epochSecond
            if (expiresAt != null) prefs[EXPIRES_AT] = expiresAt.epochSecond
            else prefs.remove(EXPIRES_AT)
            prefs[REGISTERED_TOKEN] = token
        }
    }

    override suspend fun clearRegistration() {
        dataStore.edit { prefs ->
            prefs.remove(FINGERPRINT)
            prefs.remove(REGISTERED_AT)
            prefs.remove(EXPIRES_AT)
            prefs.remove(REGISTERED_TOKEN)
        }
    }

    override suspend fun pendingUnregisterToken(): String? =
        dataStore.data.first()[PENDING_UNREGISTER]

    override suspend fun setPendingUnregisterToken(token: String?) {
        dataStore.edit { prefs ->
            if (token != null) prefs[PENDING_UNREGISTER] = token
            else prefs.remove(PENDING_UNREGISTER)
        }
    }

    companion object {
        val ENABLED = booleanPreferencesKey("onym.push.enabled")
        val FINGERPRINT = stringPreferencesKey("onym.push.lastRegistrationFingerprint")
        val REGISTERED_AT = longPreferencesKey("onym.push.lastRegisteredAtEpochSeconds")
        val EXPIRES_AT = longPreferencesKey("onym.push.registrationExpiresAtEpochSeconds")
        val REGISTERED_TOKEN = stringPreferencesKey("onym.push.lastRegisteredToken")
        val PENDING_UNREGISTER = stringPreferencesKey("onym.push.pendingUnregisterToken")
    }
}

/** Test double — in-memory, fully functional, so reconciler tests can
 * assert the durable bookkeeping without DataStore. */
class StaticPushPreferenceProvider(
    enabled: Boolean = false,
) : PushPreferenceProvider {
    private val enabledState = MutableStateFlow(enabled)
    private val fingerprintState = MutableStateFlow<String?>(null)
    var fingerprint: String?
        get() = fingerprintState.value
        set(value) {
            fingerprintState.value = value
        }
    var registeredAt: Instant? = null
    var expiresAt: Instant? = null
    var registeredToken: String? = null
    var pendingUnregister: String? = null

    override val enabledFlow: Flow<Boolean> = enabledState

    override val registeredFlow: Flow<Boolean> = fingerprintState.map { it != null }

    override suspend fun enabled(): Boolean = enabledState.value

    override suspend fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    override suspend fun lastRegistrationFingerprint(): String? = fingerprint
    override suspend fun lastRegisteredAt(): Instant? = registeredAt
    override suspend fun registrationExpiresAt(): Instant? = expiresAt
    override suspend fun lastRegisteredToken(): String? = registeredToken

    override suspend fun recordRegistration(
        fingerprint: String,
        registeredAt: Instant,
        expiresAt: Instant?,
        token: String,
    ) {
        this.fingerprint = fingerprint
        this.registeredAt = registeredAt
        this.expiresAt = expiresAt
        this.registeredToken = token
    }

    override suspend fun clearRegistration() {
        fingerprint = null
        registeredAt = null
        expiresAt = null
        registeredToken = null
    }

    override suspend fun pendingUnregisterToken(): String? = pendingUnregister

    override suspend fun setPendingUnregisterToken(token: String?) {
        pendingUnregister = token
    }
}
