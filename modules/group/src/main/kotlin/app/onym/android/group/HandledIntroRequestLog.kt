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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

    /**
     * Drop the tombstones belonging to links that were just retired.
     *
     * Phrased as "these died", not "these are all that live": the only
     * snapshots available are scoped to ONE identity, while this log
     * spans every identity in the process. A keep-set from one owner
     * would delete every other owner's tombstones, and their handled
     * requests would come back as pending on the next reconnect.
     */
    suspend fun prune(retiringPublicKeysHex: Set<String>)

    /**
     * Drop tombstones for links that no longer exist ANYWHERE on the
     * device. Safe as a keep-set only because the caller supplies every
     * owner's live keys; unattributed tombstones are kept, having no
     * link to be checked against. Run once at startup: [prune] only
     * observes retirements that happen while an identity is subscribed,
     * so links retired with the app closed would otherwise linger until
     * [MAX_ENTRIES] evicted them oldest-first — which can drop a LIVE
     * link's tombstone and bring a handled request back as pending.
     */
    suspend fun reconcile(livePublicKeysHex: Set<String>)

    /** Joiners the host declined, keyed by collapse key (joiner
     *  identity + group) rather than event id — a retry arrives as a
     *  fresh Nostr event, so an id-keyed tombstone never matches it. */
    suspend fun declinedCollapseKeys(): Set<String>

    /** Remember that this joiner was declined for this group. */
    suspend fun recordDeclined(collapseKey: String)

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

    override suspend fun prune(retiringPublicKeysHex: Set<String>) = mutex.withLock {
        if (retiringPublicKeysHex.isEmpty()) return@withLock
        val rows = load()
        val kept = rows.filter { it.introPub !in retiringPublicKeysHex }
        if (kept.size != rows.size) save(kept)
    }

    override suspend fun reconcile(livePublicKeysHex: Set<String>) = mutex.withLock {
        val rows = load()
        // An empty introPub is the unattributed marker — no link to
        // check it against, so it is never reconciled away.
        val kept = rows.filter { it.introPub.isEmpty() || it.introPub in livePublicKeysHex }
        if (kept.size != rows.size) save(kept)
    }

    override suspend fun declinedCollapseKeys(): Set<String> =
        loadDeclined().toSet()

    override suspend fun recordDeclined(collapseKey: String) = mutex.withLock {
        val keys = loadDeclined().toMutableList()
        if (keys.contains(collapseKey)) return@withLock
        keys += collapseKey
        while (keys.size > HandledIntroRequestLog.MAX_ENTRIES) keys.removeAt(0)
        dataStore.edit { it[DECLINED_KEY] = json.encodeToString(ListSerializer(String.serializer()), keys) }
        Unit
    }

    private suspend fun loadDeclined(): List<String> {
        val raw = dataStore.data.first()[DECLINED_KEY] ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        } catch (_: SerializationException) {
            emptyList()
        }
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
        val DECLINED_KEY = stringPreferencesKey("declined_collapse_keys")
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Test double, and the fallback when no durable log is wired. */
class InMemoryHandledIntroRequestLog : HandledIntroRequestLog {

    private val mutex = Mutex()
    private val rows = mutableMapOf<String, String>()
    private val declined = mutableSetOf<String>()

    override suspend fun handledIds(): Set<String> = mutex.withLock { rows.keys.toSet() }

    override suspend fun record(id: String, introPublicKeyHex: String) = mutex.withLock {
        rows[id] = introPublicKeyHex
        Unit
    }

    override suspend fun prune(retiringPublicKeysHex: Set<String>) = mutex.withLock {
        val drop = rows.filterValues { it in retiringPublicKeysHex }.keys.toList()
        drop.forEach { rows.remove(it) }
    }

    override suspend fun reconcile(livePublicKeysHex: Set<String>) = mutex.withLock {
        val drop = rows.filterValues { it.isNotEmpty() && it !in livePublicKeysHex }
            .keys.toList()
        drop.forEach { rows.remove(it) }
    }

    override suspend fun declinedCollapseKeys(): Set<String> = mutex.withLock { declined.toSet() }

    override suspend fun recordDeclined(collapseKey: String) = mutex.withLock {
        declined += collapseKey
        Unit
    }
}
