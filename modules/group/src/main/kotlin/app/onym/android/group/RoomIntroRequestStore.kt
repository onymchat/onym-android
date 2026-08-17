package app.onym.android.group

import app.onym.android.foundation.StorageEncryption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * On-disk [IntroRequestStore].
 *
 * ## Why this is persisted
 *
 * [InMemoryIntroRequestStore] was deliberately process-lifetime: the
 * approval UI was a modal the admin was expected to act on there and
 * then, and a lost request just meant the joiner re-shared. Now that the
 * request renders as a row *inside the chat thread*, disappearing on
 * process death reads as a message the app lost. The joiner has no way
 * to know their request evaporated — they are sitting on "Waiting for
 * the host to approve…" — so durability moved from nice-to-have to
 * correctness.
 *
 * The matching intro *private* keys already persist
 * (`EncryptedPrefsIntroKeyStore`), so a request restored here is still
 * decryptable after a relaunch — [JoinRequestApprover.start] re-runs its
 * decode over the restored snapshot on subscribe. A request whose intro
 * key has been revoked simply fails to decode and never renders;
 * retention below governs only how long the dead row occupies storage.
 *
 * Handled ids are tombstoned in [handledLog], which outlives the rows.
 * Deleting the row is not enough now that links are multi-use: the
 * subscription survives the approval and the relay REQ carries no
 * `since`, so every reconnect replays a request already acted on.
 *
 * Mirrors `SwiftDataIntroRequestStore` in onym-ios.
 */
class RoomIntroRequestStore(
    private val dao: IntroRequestDao,
    private val encryption: StorageEncryption,
    private val handledLog: HandledIntroRequestLog = InMemoryHandledIntroRequestLog(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : IntroRequestStore {

    private val mutex = Mutex()
    /** Event ids already acted on, mirrored from [handledLog] so the
     *  hot [record] path doesn't hit storage per inbound. */
    private var consumed: MutableSet<String> = mutableSetOf()
    private var seeded = false
    private val _requests = MutableStateFlow<List<IntroRequest>>(emptyList())
    override val requests: StateFlow<List<IntroRequest>> = _requests.asStateFlow()

    /**
     * Load what is on disk into the hot flow. Call once at startup —
     * this replay is what puts a restored request back into the thread
     * on a cold launch, without waiting for a fresh relay delivery.
     */
    suspend fun start() = mutex.withLock {
        seedConsumedLocked()
        refreshLocked()
    }

    override suspend fun record(request: IntroRequest): Boolean = mutex.withLock {
        seedConsumedLocked()
        // Already acted on: a replay must not come back as pending.
        if (request.id in consumed) return@withLock false
        if (dao.count(request.id) > 0) {
            // Backfill a row written before `firstSeenAtMillis` existed
            // (the column is nullable for migration). Its clock starts
            // now rather than at some sender-asserted past, so a
            // migrated request gets a full window instead of being swept
            // on the next read.
            //
            // Only ever stamps a NULL — see the DAO. A relay replay of
            // an already-stamped request must not reset its clock, or
            // retention never fires at all.
            dao.stampFirstSeen(request.id, now())
            return@withLock false
        }
        val row = try {
            encode(request, firstSeenAtMillis = now())
        } catch (_: Throwable) {
            return@withLock false
        }
        try {
            dao.insert(row)
        } catch (_: Throwable) {
            return@withLock false
        }
        refreshLocked()
        true
    }

    override suspend fun consume(id: String) = mutex.withLock {
        seedConsumedLocked()
        // Unconditional, so a tombstone can be laid ahead of a replay
        // that hasn't landed yet.
        consumed += id
        // Attribute to the link it arrived on so [pruneTombstones] can
        // drop it when that link is retired. With no row to attribute
        // it to it goes in unattributed rather than being dropped —
        // losing it means the handled request returns as pending on the
        // next cold start.
        val introPubHex = dao.findById(id)
            ?.let { row -> runCatching { encryption.decrypt(row.encryptedTargetIntroPublicKey) }.getOrNull() }
            ?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        handledLog.record(id, introPubHex ?: "")
        dao.delete(id)
        refreshLocked()
    }

    override suspend fun reconcileTombstones(livePublicKeysHex: Set<String>) = mutex.withLock {
        handledLog.reconcile(livePublicKeysHex)
        consumed = handledLog.handledIds().toMutableSet()
        seeded = true
    }

    override suspend fun recordDeclined(collapseKey: String) {
        handledLog.recordDeclined(collapseKey)
    }

    override suspend fun declinedCollapseKeys(): Set<String> = handledLog.declinedCollapseKeys()

    override suspend fun pruneTombstones(retiringPublicKeysHex: Set<String>) = mutex.withLock {
        handledLog.prune(retiringPublicKeysHex)
        consumed = handledLog.handledIds().toMutableSet()
        seeded = true
    }

    private suspend fun seedConsumedLocked() {
        if (seeded) return
        consumed = handledLog.handledIds().toMutableSet()
        seeded = true
    }

    /**
     * Drop requests older than [RETENTION_MILLIS], then republish.
     *
     * Persistence closed the "process death loses the request" hole, but
     * it opened a slower one: nothing else deletes these rows. A request
     * is only `consume`d when the founder acts on it, and there are
     * paths where that never happens — the founder deletes the group
     * locally while its invite link is still live, or an approve fails
     * permanently at the transport leg and is never retried. Process
     * death used to sweep those up; this is what replaces it.
     */
    private suspend fun pruneLocked() {
        dao.deleteOlderThan(now() - RETENTION_MILLIS)
    }

    /** Tombstoned ids are filtered on read as well as in [record]: the
     *  tombstone commits before the row is deleted, so a crash in that
     *  window would otherwise resurrect a handled request. */
    private suspend fun refreshLocked() {
        pruneLocked()
        _requests.value = dao.list().mapNotNull(::decode).filter { it.id !in consumed }
    }

    private fun encode(request: IntroRequest, firstSeenAtMillis: Long) = PersistedIntroRequest(
        id = request.id,
        receivedAtMillis = request.receivedAt.toEpochMilli(),
        firstSeenAtMillis = firstSeenAtMillis,
        encryptedTargetIntroPublicKey = encryption.encrypt(request.targetIntroPublicKey),
        encryptedPayload = encryption.encrypt(request.payload),
    )

    /** Tolerant decode: a row whose encrypted columns fail to decrypt is
     *  skipped rather than failing the whole read — one corrupted row
     *  shouldn't hide every other pending request. */
    private fun decode(row: PersistedIntroRequest): IntroRequest? = try {
        IntroRequest(
            id = row.id,
            targetIntroPublicKey = encryption.decrypt(row.encryptedTargetIntroPublicKey),
            payload = encryption.decrypt(row.encryptedPayload),
            receivedAt = Instant.ofEpochMilli(row.receivedAtMillis),
        )
    } catch (_: Throwable) {
        null
    }

    companion object {
        /**
         * How long an unanswered request is kept before it is dropped.
         *
         * A week is well past the point where a joiner staring at
         * "Waiting for the host to approve…" has given up and re-shared.
         * Matches the iOS constant.
         */
        const val RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
