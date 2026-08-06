package app.onym.android.group

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable record of intro-request event ids the user already acted on.
 *
 * The REQ carries no `since`, so every cold start replays an intro
 * inbox in full. The burn used to make those replays undecodable.
 *
 * Scoped by intro pubkey, so [prune] bounds the set by the links that
 * still exist. [MAX_ENTRIES] backstops one busy link.
 *
 * Mirrors `HandledIntroRequestLog.swift` from onym-ios.
 */
interface HandledIntroRequestLog {
    /** Event ids already acted on, for the reader's fast path. */
    suspend fun handledIds(): Set<String>

    /** Remember [id], attributed to the link it arrived on. */
    suspend fun record(id: String, introPublicKeyHex: String)

    /** Drop tombstones whose link no longer exists. */
    suspend fun prune(livePublicKeysHex: Set<String>)

    companion object {
        /** Ids are 64-char hex, so this caps the blob at a few hundred KB. */
        const val MAX_ENTRIES = 2_000
    }
}

/**
 * DataStore-backed: event ids aren't secret material, so
 * EncryptedSharedPreferences would be over-rotation.
 */
class DataStorePreferencesHandledIntroRequestLog(
    private val dataStore: DataStore<Preferences>,
) : HandledIntroRequestLog {

    private val mutex = Mutex()

    override suspend fun handledIds(): Set<String> =
        load().map { it.id }.toSet()

    override suspend fun record(id: String, introPublicKeyHex: String) = mutex.withLock {
        val rows = load().toMutableList()
        if (rows.any { it.id == id }) return@withLock
        rows += Row(id, introPublicKeyHex)
        // Oldest-first drop: a replay is likeliest to hit recent ids.
        while (rows.size > HandledIntroRequestLog.MAX_ENTRIES) rows.removeAt(0)
        save(rows)
    }

    override suspend fun prune(livePublicKeysHex: Set<String>) = mutex.withLock {
        val rows = load()
        val kept = rows.filter { it.introPub in livePublicKeysHex }
        if (kept.size != rows.size) save(kept)
    }

    // ─── private ──────────────────────────────────────────────────

    @Serializable
    private data class Row(val id: String, val introPub: String)

    @Serializable
    private data class Blob(val rows: List<Row>)

    private suspend fun load(): List<Row> {
        val raw = dataStore.data.first()[KEY] ?: return emptyList()
        // Corrupted blob → start over; one round of replays re-surfaces.
        return try {
            json.decodeFromString(Blob.serializer(), raw).rows
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    private suspend fun save(rows: List<Row>) {
        val encoded = json.encodeToString(Blob.serializer(), Blob(rows))
        dataStore.edit { it[KEY] = encoded }
    }

    private companion object {
        val KEY = stringPreferencesKey("handled_intro_requests")
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Test double, and the fallback when no durable log is wired. */
class InMemoryHandledIntroRequestLog : HandledIntroRequestLog {

    private val mutex = Mutex()
    private val rows = mutableMapOf<String, String>()

    override suspend fun handledIds(): Set<String> = mutex.withLock { rows.keys.toSet() }

    override suspend fun record(id: String, introPublicKeyHex: String) = mutex.withLock {
        rows[id] = introPublicKeyHex
        Unit
    }

    override suspend fun prune(livePublicKeysHex: Set<String>) = mutex.withLock {
        val drop = rows.filterValues { it !in livePublicKeysHex }.keys
        drop.forEach { rows.remove(it) }
    }
}
