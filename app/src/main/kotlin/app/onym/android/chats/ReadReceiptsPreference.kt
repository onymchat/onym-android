package app.onym.android.chats

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * The single symmetric read-receipt setting (default ON). Gates BOTH
 * sending your read receipts and honoring inbound ones, so you only
 * see others' read status if you also share yours. Delivered receipts
 * are unconditional and not covered here.
 *
 * Read-only seam over the store so the dispatcher / VM depend on this
 * rather than DataStore directly and tests can swap it. Mirrors the
 * shape of [app.onym.android.chain.NetworkPreferenceProvider].
 */
interface ReadReceiptsPreferenceProvider {
    /** Synchronous one-shot read (default `true`). */
    fun current(): Boolean

    /** Reactive read for the Settings toggle. */
    val flow: Flow<Boolean>

    suspend fun set(enabled: Boolean)
}

/** Production impl — DataStore Preferences, default ON when unset. */
class DataStoreReadReceiptsPreferenceProvider(
    private val dataStore: DataStore<Preferences>,
) : ReadReceiptsPreferenceProvider {

    override fun current(): Boolean = runBlocking {
        dataStore.data.first()[SEND_READ_RECEIPTS] ?: true
    }

    override val flow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SEND_READ_RECEIPTS] ?: true
    }

    override suspend fun set(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SEND_READ_RECEIPTS] = enabled }
    }

    companion object {
        val SEND_READ_RECEIPTS = booleanPreferencesKey("onym.chat.sendReadReceipts")
    }
}

/** Test fake — returns whatever was passed in. */
class StaticReadReceiptsPreferenceProvider(
    private val value: Boolean,
) : ReadReceiptsPreferenceProvider {
    override fun current(): Boolean = value
    override val flow: Flow<Boolean> = flowOf(value)
    override suspend fun set(enabled: Boolean) {
        throw UnsupportedOperationException("StaticReadReceiptsPreferenceProvider is read-only")
    }
}
